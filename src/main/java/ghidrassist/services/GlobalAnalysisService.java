package ghidrassist.services;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ghidra.program.model.address.Address;
import ghidra.program.model.data.StringDataInstance;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.util.Msg;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

import ghidrassist.GhidrAssistPlugin;
import ghidrassist.LlmApi;
import ghidrassist.apiprovider.APIProviderConfig;
import ghidrassist.core.ActionConstants;
import ghidrassist.core.ActionParser;
import ghidrassist.core.CodeUtils;
import ghidrassist.graphrag.GraphRAGService;
import ghidrassist.services.symgraph.SymGraphUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Binary-wide auto-analyze orchestrator.
 *
 * Scope fence (enforced in extractProposals):
 *   rename_function, rename_variable (params only), retype_variable (params only, conf==1.0),
 *   auto_create_struct.
 *
 * Threading: synchronous from caller; must not run on the EDT.
 */
public class GlobalAnalysisService {

    private static final Gson GSON = new Gson();

    private final GhidrAssistPlugin plugin;
    private final APIProviderConfig apiConfig;

    public GlobalAnalysisService(GhidrAssistPlugin plugin, APIProviderConfig apiConfig) {
        this.plugin = plugin;
        this.apiConfig = apiConfig;
    }

    /**
     * Run the batch analysis over a caller-provided function set. Proposals are streamed to the
     * sink as produced; a summary is returned at the end. The caller owns the
     * {@link TaskMonitor} and may cancel at any time.
     *
     * <p>{@code functions} must be non-null. Both "Analyze All" and "Analyze Selected" funnel
     * through this single entry so all filtering/ordering rules stay in one place.</p>
     */
    public Summary analyze(Program program, List<Function> functions, GlobalAnalysisConfig config,
                           TaskMonitor monitor, ProposalSink sink) throws CancelledException {
        if (program == null) throw new IllegalArgumentException("program is null");
        if (functions == null) throw new IllegalArgumentException("functions is null");
        if (config == null) config = GlobalAnalysisConfig.defaults();
        if (monitor == null) monitor = TaskMonitor.DUMMY;
        if (sink == null) sink = ProposalSink.NO_OP;

        LlmApi llmApi = new LlmApi(apiConfig, plugin);
        try {
            ghidrassist.AnalysisDB adb = new ghidrassist.AnalysisDB();
            String savedEffort = adb.getReasoningEffort(program.getExecutableSHA256());
            if (savedEffort != null && !"none".equalsIgnoreCase(savedEffort)) {
                llmApi.setReasoningConfig(ghidrassist.apiprovider.ReasoningConfig.fromString(savedEffort));
            }
        } catch (Exception e) {
            Msg.warn(this, "Failed to load reasoning effort for global analysis: " + e.getMessage());
        }

        List<Function> candidates = filterAndOrderCandidates(functions, config, monitor);
        monitor.checkCancelled();

        List<Function> ordered = orderLeavesFirst(candidates, monitor);
        monitor.checkCancelled();

        GraphRAGService graphRag = safeGetGraphRag();

        Summary summary = new Summary();
        summary.candidatesConsidered = ordered.size();

        ProposalSink finalSink = sink;

        int functionIndex = 0;
        monitor.setIndeterminate(false);
        monitor.initialize(ordered.size());
        monitor.setMessage("Global auto-analyze: " + ordered.size() + " candidate function(s).");

        for (Function function : ordered) {
            if (monitor.isCancelled()) break;
            functionIndex++;
            monitor.setProgress(functionIndex);
            monitor.setMessage(String.format("[%d/%d] %s @ %s",
                functionIndex, ordered.size(), function.getName(), function.getEntryPoint()));

            String code;
            try {
                code = decompile(function, monitor);
            } catch (Exception e) {
                Msg.warn(this, "Decompile failed for " + function.getName() + ": " + e.getMessage());
                summary.decompileFailures++;
                continue;
            }
            if (code == null) { summary.decompileFailures++; continue; }

            FunctionEvidence evidence = buildEvidence(program, function, code, graphRag, monitor, config);

            for (String action : config.actions) {
                if (monitor.isCancelled()) break;
                String promptTemplate = ActionConstants.GLOBAL_ACTION_PROMPTS.get(action);
                if (promptTemplate == null) continue;

                String prompt = promptTemplate
                    .replace("{current_name}", function.getName())
                    .replace("{code}", truncate(code, config.maxCodeChars))
                    .replace("{context}", truncate(evidence.contextBlock, config.maxContextChars));

                Map<String, Object> schema = findSchema(action);
                if (schema == null) continue;
                List<Map<String, Object>> schemas = new ArrayList<>();
                schemas.add(schema);

                String response;
                try {
                    response = requestBlocking(llmApi, prompt, schemas, monitor, config.llmTimeoutSeconds);
                } catch (CancelledException ce) {
                    throw ce;
                } catch (Exception e) {
                    Msg.warn(this, "LLM request failed for " + function.getName() + "/" + action
                        + ": " + e.getMessage());
                    summary.llmFailures++;
                    continue;
                }
                if (response == null || response.isBlank()) continue;

                List<ProposedAction> proposals = extractProposals(response, action, function, evidence);
                for (ProposedAction p : proposals) {
                    summary.proposalsProduced++;
                    finalSink.accept(p);
                }
            }
        }

        monitor.setMessage("Global auto-analyze complete. "
            + summary.proposalsProduced + " proposals from "
            + summary.candidatesConsidered + " candidates.");
        return summary;
    }

    private List<Function> filterAndOrderCandidates(List<Function> input, GlobalAnalysisConfig config,
                                                    TaskMonitor monitor) throws CancelledException {
        List<Function> out = new ArrayList<>();
        for (Function f : input) {
            monitor.checkCancelled();
            if (f == null) continue;
            if (f.isThunk() || f.isExternal()) continue;
            if (f.getBody() == null || f.getBody().isEmpty()) continue;
            if (!SymGraphUtils.isDefaultName(f.getName())) continue;
            out.add(f);
        }
        // Largest functions first - user preference for processing order.
        out.sort((a, b) -> Long.compare(b.getBody().getNumAddresses(), a.getBody().getNumAddresses()));
        if (config.maxCandidates > 0 && out.size() > config.maxCandidates) {
            out = new ArrayList<>(out.subList(0, config.maxCandidates));
        }
        return out;
    }

    /**
     * Leaves-first order over the candidate subgraph. Functions whose callees (restricted to
     * the candidate set) are all emitted come first. Cycle residue is flushed last in insertion
     * order. Not a true SCC condensation; deterministic and cheap.
     */
    private List<Function> orderLeavesFirst(List<Function> candidates, TaskMonitor monitor)
            throws CancelledException {
        Set<Function> candidateSet = new HashSet<>(candidates);
        List<Function> result = new ArrayList<>(candidates.size());
        Set<Function> emitted = new HashSet<>();
        Deque<Function> pending = new ArrayDeque<>(candidates);

        int stagnationLimit = pending.size() + 1;
        int stagnation = 0;
        while (!pending.isEmpty() && stagnation < stagnationLimit) {
            monitor.checkCancelled();
            Function f = pending.pollFirst();
            boolean ready = true;
            try {
                Set<Function> callees = f.getCalledFunctions(monitor);
                for (Function c : callees) {
                    if (candidateSet.contains(c) && !emitted.contains(c) && !c.equals(f)) {
                        ready = false;
                        break;
                    }
                }
            } catch (Exception e) {
                ready = true;
            }
            if (ready) {
                result.add(f);
                emitted.add(f);
                stagnation = 0;
            } else {
                pending.addLast(f);
                stagnation++;
            }
        }
        while (!pending.isEmpty()) {
            Function f = pending.pollFirst();
            if (!emitted.contains(f)) { result.add(f); emitted.add(f); }
        }
        return result;
    }

    private String decompile(Function function, TaskMonitor monitor) {
        String code = CodeUtils.getFunctionCode(function, monitor);
        if (code == null || code.startsWith("Failed to decompile")) return null;
        return code;
    }

    private FunctionEvidence buildEvidence(Program program, Function function, String code,
                                           GraphRAGService graphRag, TaskMonitor monitor,
                                           GlobalAnalysisConfig config) {
        FunctionEvidence ev = new FunctionEvidence();
        ev.referencedStrings = collectReferencedStrings(program, function, config.maxStrings, monitor);
        ev.namedCallees = new ArrayList<>();
        try {
            for (Function callee : function.getCalledFunctions(monitor)) {
                if (callee == null) continue;
                String name = callee.getName();
                if (name == null || name.isEmpty()) continue;
                if (SymGraphUtils.isDefaultName(name)) continue;
                ev.namedCallees.add(name);
                if (ev.namedCallees.size() >= config.maxNamedCallees) break;
            }
        } catch (Exception ignore) {}

        String ragContext = "";
        if (graphRag != null) {
            try {
                ragContext = graphRag.buildFunctionContext(function, 1);
                if (ragContext != null && ragContext.startsWith("Function not yet indexed")) {
                    ragContext = "";
                    ev.summaryAvailable = false;
                } else if (ragContext != null && !ragContext.isEmpty()) {
                    ev.summaryAvailable = ragContext.contains("**Summary:**");
                }
            } catch (Exception e) {
                ragContext = "";
            }
        }

        StringBuilder ctx = new StringBuilder();
        if (!ev.referencedStrings.isEmpty()) {
            ctx.append("Referenced strings:\n");
            for (String s : ev.referencedStrings) ctx.append("  - ").append(s).append('\n');
            ctx.append('\n');
        }
        if (!ev.namedCallees.isEmpty()) {
            ctx.append("Named callees: ").append(String.join(", ", ev.namedCallees)).append("\n\n");
        }
        if (ragContext != null && !ragContext.isEmpty()) ctx.append(ragContext);
        ev.contextBlock = ctx.toString();
        return ev;
    }

    private List<String> collectReferencedStrings(Program program, Function function, int max, TaskMonitor monitor) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Listing listing = program.getListing();
        ReferenceManager refMgr = program.getReferenceManager();
        try {
            for (Address addr : function.getBody().getAddresses(true)) {
                if (monitor != null && monitor.isCancelled()) break;
                Instruction insn = listing.getInstructionAt(addr);
                if (insn == null) continue;
                Reference[] refs = refMgr.getReferencesFrom(addr);
                if (refs == null) continue;
                for (Reference r : refs) {
                    Address to = r.getToAddress();
                    if (to == null) continue;
                    Data d = listing.getDataAt(to);
                    if (d == null) continue;
                    StringDataInstance sdi = StringDataInstance.getStringDataInstance(d);
                    if (sdi == null || sdi == StringDataInstance.NULL_INSTANCE) continue;
                    String s = sdi.getStringValue();
                    if (s == null) continue;
                    s = s.trim();
                    if (s.length() < 3) continue;
                    if (s.length() > 120) s = s.substring(0, 120) + "...";
                    out.add(s);
                    if (out.size() >= max) return new ArrayList<>(out);
                }
            }
        } catch (Exception ignore) {}
        return new ArrayList<>(out);
    }

    private String requestBlocking(LlmApi llmApi, String prompt, List<Map<String, Object>> functionSchemas,
                                   TaskMonitor monitor, long timeoutSeconds) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        llmApi.sendRequestAsyncWithFunctions(prompt, functionSchemas, new LlmApi.LlmResponseHandler() {
            @Override public void onStart() {}
            @Override public void onUpdate(String partialResponse) {}
            @Override public void onComplete(String fullResponse) { future.complete(fullResponse); }
            @Override public void onError(Throwable error) { future.completeExceptionally(error); }
            @Override public boolean shouldContinue() { return !monitor.isCancelled(); }
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            if (monitor.isCancelled()) {
                try { llmApi.cancelCurrentRequest(); } catch (Exception ignore) {}
                throw new CancelledException();
            }
            try {
                return future.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                if (System.nanoTime() > deadline) {
                    try { llmApi.cancelCurrentRequest(); } catch (Exception ignore) {}
                    throw new Exception("LLM request exceeded " + timeoutSeconds + "s timeout.");
                }
            }
        }
    }

    private Map<String, Object> findSchema(String actionName) {
        if (actionName == null) return null;
        for (Map<String, Object> tmpl : ActionConstants.FN_TEMPLATES) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fn = (Map<String, Object>) tmpl.get("function");
            if (fn == null) continue;
            Object name = fn.get("name");
            if (actionName.equals(name)) return tmpl;
        }
        return null;
    }

    /**
     * Convert an LLM response into zero or more {@link ProposedAction} rows.
     * Scope fence:
     *   rename_variable: dropped if var_name not in current parameters.
     *   retype_variable: dropped if var_name not in current parameters OR confidence < 1.0.
     *   Any action whose name doesn't match the solicited one is dropped.
     */
    private List<ProposedAction> extractProposals(String response, String solicitedAction,
                                                  Function target, FunctionEvidence evidence) {
        List<ProposedAction> out = new ArrayList<>();
        JsonArray toolCalls = parseToolCallsArray(response);
        if (toolCalls == null) return out;

        Set<String> parameterNames = new HashSet<>();
        try {
            for (Parameter p : target.getParameters()) parameterNames.add(p.getName());
        } catch (Exception ignore) {}

        for (JsonElement el : toolCalls) {
            try {
                if (!el.isJsonObject()) continue;
                JsonObject call = el.getAsJsonObject();
                JsonObject fn = call.has("function") ? call.getAsJsonObject("function") : call;
                if (fn == null || !fn.has("name") || fn.get("name").isJsonNull() || !fn.has("arguments")) continue;
                String name;
                try {
                    name = fn.get("name").getAsString();
                } catch (Exception e) { continue; }
                if (!solicitedAction.equals(name)) continue;

                JsonObject args = toArgumentsObject(fn.get("arguments"));
                if (args == null) continue;

                if ("rename_variable".equals(name)) {
                    if (!args.has("var_name") || args.get("var_name").isJsonNull()) continue;
                    if (!parameterNames.contains(args.get("var_name").getAsString())) continue;
                } else if ("retype_variable".equals(name)) {
                    if (!args.has("var_name") || args.get("var_name").isJsonNull()) continue;
                    if (!parameterNames.contains(args.get("var_name").getAsString())) continue;
                    if (extractConfidence(args) < 0.9999) continue;
                } else if ("set_signature".equals(name)) {
                    // Accept either nested parameters array OR flattened parallel arrays (parameter_names, parameter_types)
                    boolean ok = false;
                    if (args.has("parameters") && args.get("parameters").isJsonArray()) {
                        int proposedCount = args.getAsJsonArray("parameters").size();
                        if (proposedCount == parameterNames.size()) ok = true;
                    } else if (args.has("parameter_names") && args.has("parameter_types")
                            && args.get("parameter_names").isJsonArray() && args.get("parameter_types").isJsonArray()) {
                        int n = args.getAsJsonArray("parameter_names").size();
                        int m = args.getAsJsonArray("parameter_types").size();
                        if (n == m && n == parameterNames.size()) ok = true;
                    }
                    if (!ok) continue;
                    if (!args.has("new_return_type") || args.get("new_return_type").isJsonNull()) continue;
                    if (extractConfidence(args) < 0.9999) continue;
                }

                args.addProperty(ActionParser.KEY_TARGET_ENTRY_ADDRESS, target.getEntryPoint().toString());
                args.addProperty(ActionParser.KEY_TARGET_FUNC_NAME, target.getName());

                ProposedAction p = new ProposedAction();
                p.action = name;
                p.target = target;
                p.argumentsJson = args.toString();
                p.confidence = extractConfidence(args);
                p.evidence = evidence;
                p.description = describe(name, args);
                out.add(p);
            } catch (Exception e) {
                Msg.debug(this, "Skipping malformed tool call in " + target.getName() + ": " + e.getMessage());
            }
        }
        return out;
    }


    private static JsonArray parseToolCallsArray(String response) {
        if (response == null || response.isBlank()) return null;
        try {
            JsonElement root = GSON.fromJson(response, JsonElement.class);
            if (root == null || !root.isJsonObject()) return null;
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("tool_calls") && obj.get("tool_calls").isJsonArray()) {
                return obj.getAsJsonArray("tool_calls");
            }
            if (obj.has("choices") && obj.get("choices").isJsonArray()) {
                JsonArray choices = obj.getAsJsonArray("choices");
                if (choices.size() > 0 && choices.get(0).isJsonObject()) {
                    JsonObject msg = choices.get(0).getAsJsonObject().has("message")
                        ? choices.get(0).getAsJsonObject().getAsJsonObject("message") : null;
                    if (msg != null && msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()) {
                        return msg.getAsJsonArray("tool_calls");
                    }
                }
            }
            if (obj.has("content") && obj.get("content").isJsonArray()) {
                JsonArray content = obj.getAsJsonArray("content");
                JsonArray out = new JsonArray();
                for (JsonElement c : content) {
                    try {
                        if (!c.isJsonObject()) continue;
                        JsonObject co = c.getAsJsonObject();
                        if (!co.has("type") || co.get("type").isJsonNull()) continue;
                        String type;
                        try { type = co.get("type").getAsString(); }
                        catch (Exception e) { continue; }
                        if (!"tool_use".equals(type)) continue;
                        JsonObject tc = new JsonObject();
                        JsonObject fn = new JsonObject();
                        if (co.has("name") && !co.get("name").isJsonNull()) {
                            try { fn.addProperty("name", co.get("name").getAsString()); }
                            catch (Exception e) { continue; }
                        }
                        if (co.has("input")) fn.add("arguments", co.get("input"));
                        tc.add("function", fn);
                        out.add(tc);
                    } catch (Exception itemEx) {
                        continue;
                    }
                }
                if (out.size() > 0) return out;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static JsonObject toArgumentsObject(JsonElement argsElement) {
        try {
            if (argsElement.isJsonObject()) return argsElement.getAsJsonObject().deepCopy();
            if (argsElement.isJsonPrimitive()) {
                return GSON.fromJson(argsElement.getAsString(), JsonObject.class);
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static double extractConfidence(JsonObject args) {
        if (!args.has("confidence")) return 0.0;
        try { return args.get("confidence").getAsDouble(); } catch (Exception e) { return 0.0; }
    }

    private static String describe(String action, JsonObject args) {
        try {
            switch (action) {
                case "rename_function": return safeStr(args, "new_name");
                case "rename_variable": return safeStr(args, "var_name") + " -> " + safeStr(args, "new_name");
                case "retype_variable": return safeStr(args, "var_name") + " :: " + safeStr(args, "new_type");
                case "auto_create_struct": return safeStr(args, "var_name");
                case "set_signature": {
                    String ret = safeStr(args, "new_return_type");
                    StringBuilder sb = new StringBuilder();
                    sb.append(ret.isEmpty() ? "?" : ret).append(" (");
                    if (args.has("parameters") && args.get("parameters").isJsonArray()) {
                        JsonArray arr = args.getAsJsonArray("parameters");
                        for (int i = 0; i < arr.size(); i++) {
                            if (i > 0) sb.append(", ");
                            if (arr.get(i).isJsonObject()) {
                                JsonObject p = arr.get(i).getAsJsonObject();
                                String pt = safeStr(p, "type");
                                String pn = safeStr(p, "name");
                                sb.append(pt.isEmpty() ? "?" : pt).append(" ").append(pn.isEmpty() ? "?" : pn);
                            }
                        }
                    }
                    sb.append(")");
                    return sb.toString();
                }
                default: return "";
            }
        } catch (Exception e) { return ""; }
    }

    private static String safeStr(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (max <= 0) return s;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n// ... (truncated)";
    }

    private GraphRAGService safeGetGraphRag() {
        try {
            return GraphRAGService.getInstance(new ghidrassist.AnalysisDB());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Tuning knobs. All values are runtime-configurable from the Global Actions tab and default
     * to the constants in {@link ActionConstants}. Mutating fields directly is intentional; the
     * UI layer constructs the instance per-run.
     */
    public static final class GlobalAnalysisConfig {
        public Set<String> actions;
        public int maxCandidates;
        public int maxCodeChars;
        public int maxContextChars;
        public int maxStrings;
        public int maxNamedCallees;
        public long llmTimeoutSeconds;
        public double autoApplyConfidence;

        public static GlobalAnalysisConfig defaults() {
            GlobalAnalysisConfig c = new GlobalAnalysisConfig();
            c.actions = new LinkedHashSet<>();
            c.actions.add("rename_function");
            c.actions.add("rename_variable");
            c.actions.add("retype_variable");
            c.actions.add("auto_create_struct");
            c.actions.add("set_signature");
            c.maxCandidates = ActionConstants.GLOBAL_MAX_CANDIDATES;
            c.maxCodeChars = ActionConstants.GLOBAL_MAX_CODE_CHARS;
            c.maxContextChars = ActionConstants.GLOBAL_MAX_CONTEXT_CHARS;
            c.maxStrings = ActionConstants.GLOBAL_MAX_STRINGS;
            c.maxNamedCallees = ActionConstants.GLOBAL_MAX_NAMED_CALLEES;
            c.llmTimeoutSeconds = ActionConstants.GLOBAL_LLM_TIMEOUT_SECONDS;
            c.autoApplyConfidence = ActionConstants.AUTO_APPLY_CONFIDENCE;
            return c;
        }
    }

    public static final class FunctionEvidence {
        public List<String> referencedStrings = new ArrayList<>();
        public List<String> namedCallees = new ArrayList<>();
        public boolean summaryAvailable = false;
        public String contextBlock = "";

        /**
         * Non-model corroboration score consumed by the apply-gate in TabController. A proposal is
         * eligible for silent auto-apply only if high model confidence is paired with at least one
         * corroborating signal.
         */
        public int corroborationSignals() {
            int n = 0;
            if (!referencedStrings.isEmpty()) n++;
            if (!namedCallees.isEmpty()) n++;
            if (summaryAvailable) n++;
            return n;
        }
    }

    public static final class ProposedAction {
        public String action;
        public Function target;
        public String argumentsJson;
        public double confidence;
        public String description;
        public FunctionEvidence evidence;
    }

    public interface ProposalSink {
        void accept(ProposedAction proposal);
        ProposalSink NO_OP = p -> {};
    }

    public static final class Summary {
        public int candidatesConsidered;
        public int proposalsProduced;
        public int decompileFailures;
        public int llmFailures;
    }
}
