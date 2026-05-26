package ghidrassist.core;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import javax.swing.table.DefaultTableModel;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;

import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;

public class ActionParser {
    private static final Gson gson = new Gson();

    // Synthetic keys stamped into the arguments JSON so ActionExecutor can resolve
    // the target function without relying on the cursor position. Prefixed with
    // underscore so they can never collide with real schema fields. The entry
    // address is serialized as a full Ghidra Address string (space:offset form)
    // to remain unambiguous across segmented / overlay programs - a raw offset
    // alone cannot round-trip through program.getAddressFactory().getAddress().
    public static final String KEY_TARGET_ENTRY_ADDRESS = "_target_entry_address";
    public static final String KEY_TARGET_FUNC_NAME = "_target_func_name";

    /**
     * Parse the LLM response and display actions in the table model.
     * Backward-compatible entry point with no target context - actions parsed
     * via this path will fall back to the cursor address at execution time.
     */
    public static void parseAndDisplay(String response, DefaultTableModel model) throws Exception {
        parseAndDisplay(response, model, null, null);
    }

    /**
     * Parse the LLM response and stamp the supplied default target function onto
     * every action row. Intended for the per-function Analyze workflow where the
     * LLM was prompted about exactly one function; the stamp ensures the Apply
     * step still resolves the correct function even if the user navigates away.
     *
     * @param response Raw response from LLM
     * @param model Table model to populate
     * @param defaultTarget Default function to stamp when a tool_call omits func_name (nullable)
     * @param programForLookup Program used to resolve func_name -> entry offset (nullable)
     */
    public static void parseAndDisplay(String response, DefaultTableModel model,
                                       Function defaultTarget, Program programForLookup) throws Exception {
        String jsonStr = extractToolCallsJson(response);
        JsonObject jsonObject = parseJson(jsonStr);

        if (!jsonObject.has("tool_calls")) {
            throw new Exception("Response does not contain 'tool_calls' field");
        }

        JsonArray toolCallsArray = jsonObject.getAsJsonArray("tool_calls");
        processToolCalls(toolCallsArray, model, defaultTarget, programForLookup);
    }
    
    /**
     * Extract tool calls JSON from various response formats (Anthropic, OpenAI, etc.).
     */
    private static String extractToolCallsJson(String response) throws Exception {
        // First check if this is already a tool calls JSON object
        try {
            JsonObject responseObj = gson.fromJson(response, JsonObject.class);

            if (responseObj != null) {
                // Check if this is already tool calls JSON
                if (responseObj.has("tool_calls")) {
                    return response;
                }

                // Check for OpenAI format: choices[].message.tool_calls
                if (responseObj.has("choices")) {
                    JsonArray choices = responseObj.getAsJsonArray("choices");
                    if (choices.size() > 0) {
                        JsonObject choice = choices.get(0).getAsJsonObject();
                        if (choice.has("message")) {
                            JsonObject message = choice.getAsJsonObject("message");

                            // OpenAI style tool_calls in message
                            if (message.has("tool_calls")) {
                                JsonArray toolCalls = message.getAsJsonArray("tool_calls");
                                JsonObject result = new JsonObject();
                                result.add("tool_calls", toolCalls);
                                return gson.toJson(result);
                            }

                            // Anthropic/Bedrock style - content array with tool_use blocks
                            if (message.has("content") && message.get("content").isJsonArray()) {
                                JsonArray convertedToolCalls = convertAnthropicToolUseToToolCalls(
                                        message.getAsJsonArray("content"));
                                if (convertedToolCalls != null && convertedToolCalls.size() > 0) {
                                    JsonObject result = new JsonObject();
                                    result.add("tool_calls", convertedToolCalls);
                                    return gson.toJson(result);
                                }
                            }
                        }
                    }
                }

                // Check if this is an Anthropic response with content array at top level
                if (responseObj.has("content") && responseObj.get("content").isJsonArray()) {
                    JsonArray contentArray = responseObj.getAsJsonArray("content");

                    // First check for tool_use blocks (Anthropic native format)
                    JsonArray convertedToolCalls = convertAnthropicToolUseToToolCalls(contentArray);
                    if (convertedToolCalls != null && convertedToolCalls.size() > 0) {
                        JsonObject result = new JsonObject();
                        result.add("tool_calls", convertedToolCalls);
                        return gson.toJson(result);
                    }

                    // Fall back to checking for text content with embedded JSON
                    if (contentArray.size() > 0) {
                        JsonObject firstContent = contentArray.get(0).getAsJsonObject();
                        if (firstContent.has("type") && "text".equals(firstContent.get("type").getAsString())
                            && firstContent.has("text")) {
                            String textContent = firstContent.get("text").getAsString();
                            return preprocessJsonResponse(textContent);
                        }
                    }
                }
            }
        } catch (JsonSyntaxException e) {
            // Not a JSON object, treat as raw text that needs preprocessing
        }

        // This is likely text content from Anthropic provider that needs preprocessing
        return preprocessJsonResponse(response);
    }

    /**
     * Convert Anthropic tool_use content blocks to OpenAI-style tool_calls array.
     *
     * Anthropic format:
     *   { "type": "tool_use", "id": "...", "name": "func_name", "input": {...} }
     *
     * OpenAI format:
     *   { "function": { "name": "func_name", "arguments": "{...}" } }
     */
    private static JsonArray convertAnthropicToolUseToToolCalls(JsonArray contentArray) {
        JsonArray toolCalls = new JsonArray();

        for (JsonElement item : contentArray) {
            if (!item.isJsonObject()) continue;

            JsonObject contentBlock = item.getAsJsonObject();
            if (!contentBlock.has("type")) continue;

            String type = contentBlock.get("type").getAsString();
            if ("tool_use".equals(type)) {
                JsonObject toolCall = new JsonObject();
                JsonObject function = new JsonObject();

                // Get function name
                if (contentBlock.has("name")) {
                    function.addProperty("name", contentBlock.get("name").getAsString());
                }

                // Get arguments (called "input" in Anthropic format)
                if (contentBlock.has("input")) {
                    // Convert input object to JSON string (OpenAI stores arguments as string)
                    function.add("arguments", contentBlock.get("input"));
                }

                toolCall.add("function", function);

                // Preserve ID if present
                if (contentBlock.has("id")) {
                    toolCall.addProperty("id", contentBlock.get("id").getAsString());
                }

                toolCalls.add(toolCall);
            }
        }

        return toolCalls;
    }

    /**
     * Preprocess the response to extract JSON from potential code blocks.
     */
    private static String preprocessJsonResponse(String response) {
        String json = response.trim();

        // Define regex patterns to match code block markers
        Pattern codeBlockPattern = Pattern.compile("(?s)^[`']{3}(\\w+)?\\s*(.*?)\\s*[`']{3}$");
        Matcher matcher = codeBlockPattern.matcher(json);

        if (matcher.find()) {
            // Extract the content inside the code block
            json = matcher.group(2).trim();
        } else {
            // If no code block markers, attempt to find the JSON content directly
            // Remove any leading or trailing quotes (but be careful about JSON strings)
            if ((json.startsWith("\"") && json.endsWith("\"")) || 
                (json.startsWith("'") && json.endsWith("'"))) {
                // Only remove if it's wrapping the entire content, not part of JSON
                String withoutQuotes = json.substring(1, json.length() - 1).trim();
                try {
                    // Test if removing quotes gives us valid JSON
                    gson.fromJson(withoutQuotes, JsonElement.class);
                    json = withoutQuotes;
                } catch (JsonSyntaxException e) {
                    // Keep original if removing quotes breaks JSON
                }
            }
        }
        
        // Handle escaped quotes in JSON strings from Anthropic
        // This handles the case where the JSON content itself has escaped quotes
        if (json.contains("\\\"")) {
            // Try to parse with escaped quotes
            try {
                gson.fromJson(json, JsonElement.class);
                // If it parses, return as-is
                return json;
            } catch (JsonSyntaxException e) {
                // If parsing fails, try unescaping quotes
                json = json.replace("\\\"", "\"");
            }
        }
        
        // Clean up escaped whitespace
        json = json.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r");
        
        // Clean up some specific malformed patterns but preserve valid escapes
        json = json.replace(":\"{\"", ":{\"").replace("\"}\"}", "\"}}");
        
        return json;
    }
    
    /**
     * Parse JSON string into JsonObject with lenient parsing.
     */
    private static JsonObject parseJson(String jsonStr) throws JsonSyntaxException {
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            throw new JsonSyntaxException("Empty JSON string");
        }
        JsonReader jsonReader = new JsonReader(new StringReader(jsonStr));
        jsonReader.setLenient(true);
        JsonElement jsonElement = gson.fromJson(jsonReader, JsonElement.class);
        
        if (jsonElement == null || !jsonElement.isJsonObject()) {
            throw new JsonSyntaxException("Unexpected JSON structure in response");
        }
        
        return jsonElement.getAsJsonObject();
    }
    
    private static void processToolCalls(JsonArray toolCallsArray, DefaultTableModel model,
                                         Function defaultTarget, Program programForLookup) {
        List<String> validFunctions = new ArrayList<>();
        for (Map<String, Object> fnTemplate : ActionConstants.FN_TEMPLATES) {
            @SuppressWarnings("unchecked")
            Map<String, Object> functionMap = (Map<String, Object>) fnTemplate.get("function");
            validFunctions.add(functionMap.get("name").toString());
        }

        for (JsonElement toolCallElement : toolCallsArray) {
            if (!toolCallElement.isJsonObject()) {
                continue;
            }

            JsonObject toolCallObject;
            if (toolCallElement.getAsJsonObject().has("function")) {
            	toolCallObject = toolCallElement.getAsJsonObject().get("function").getAsJsonObject();
            } else {
            	toolCallObject = toolCallElement.getAsJsonObject();
            }

            if (!toolCallObject.has("name") || !toolCallObject.has("arguments")) {
                continue;
            }

            String functionName = toolCallObject.get("name").getAsString();
            JsonObject arguments;

            JsonElement argumentsElement = toolCallObject.get("arguments");
            if (argumentsElement.isJsonObject()) {
                arguments = argumentsElement.getAsJsonObject();
            } else if (argumentsElement.isJsonPrimitive()) {
                try {
                    arguments = gson.fromJson(argumentsElement.getAsString(), JsonObject.class);
                } catch (JsonSyntaxException e) {
                    continue;
                }
            } else {
                continue;
            }

            if (!validFunctions.contains(functionName)) {
                continue;
            }

            stampTargetFunction(arguments, defaultTarget, programForLookup);

            Object[] rowData = new Object[]{
                Boolean.FALSE,
                functionName.replace("_", " "),
                formatDescription(functionName, arguments),
                "",
                arguments.toString()
            };
            model.addRow(rowData);
        }
    }

    private static void stampTargetFunction(JsonObject arguments, Function defaultTarget,
                                            Program programForLookup) {
        Function resolved = null;

        if (programForLookup != null && arguments.has("func_name")) {
            JsonElement fn = arguments.get("func_name");
            if (fn.isJsonPrimitive() && fn.getAsJsonPrimitive().isString()) {
                String requestedName = fn.getAsString();
                if (requestedName != null && !requestedName.isBlank()) {
                    resolved = findFunctionByName(programForLookup, requestedName.strip());
                }
            }
        }

        if (resolved == null) {
            resolved = defaultTarget;
        }

        if (resolved == null) {
            return;
        }

        arguments.addProperty(KEY_TARGET_ENTRY_ADDRESS,
            resolved.getEntryPoint().toString());
        arguments.addProperty(KEY_TARGET_FUNC_NAME, resolved.getName());
    }

    private static Function findFunctionByName(Program program, String name) {
        FunctionManager fm = program.getFunctionManager();
        for (Function f : fm.getFunctions(true)) {
            if (name.equals(f.getName())) {
                return f;
            }
        }
        return null;
    }
    
    /**
     * Format the description based on action type and arguments.
     */
    private static String formatDescription(String functionName, JsonObject arguments) {
        try {
            switch (functionName) {
                case "rename_function":
                    return arguments.get("new_name").getAsString();
                    
                case "rename_variable":
                    return arguments.get("var_name").getAsString() + " -> " + 
                           arguments.get("new_name").getAsString();
                    
                case "retype_variable":
                    return arguments.get("var_name").getAsString() + " -> " + 
                           arguments.get("new_type").getAsString();
                    
                case "auto_create_struct":
                    return arguments.get("var_name").getAsString();

                case "set_signature": {
                    String ret = arguments.has("new_return_type") && !arguments.get("new_return_type").isJsonNull()
                        ? arguments.get("new_return_type").getAsString() : "?";
                    StringBuilder sb = new StringBuilder();
                    sb.append(ret).append(" (");
                    if (arguments.has("parameters") && arguments.get("parameters").isJsonArray()) {
                        com.google.gson.JsonArray arr = arguments.getAsJsonArray("parameters");
                        for (int i = 0; i < arr.size(); i++) {
                            if (i > 0) sb.append(", ");
                            com.google.gson.JsonObject p = arr.get(i).getAsJsonObject();
                            String pt = p.has("type") && !p.get("type").isJsonNull() ? p.get("type").getAsString() : "?";
                            String pn = p.has("name") && !p.get("name").isJsonNull() ? p.get("name").getAsString() : "?";
                            sb.append(pt).append(" ").append(pn);
                        }
                    }
                    sb.append(")");
                    return sb.toString();
                }

                default:
                    return "";
            }
        } catch (Exception e) {
            return "Error: Failed to parse arguments";
        }
    }
}
