package ghidrassist.core;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.decompiler.util.FillOutStructureHelper;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressFactory;
import ghidra.program.model.data.CategoryPath;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.data.InvalidDataTypeException;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.Structure;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.listing.Program;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.listing.Function.FunctionUpdateType;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighFunctionDBUtil;
import ghidra.program.model.pcode.HighSymbol;
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.Msg;
import ghidra.util.data.DataTypeParser;
import ghidrassist.AnalysisDB;
import ghidrassist.services.StructSignatureRegistry;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;
import ghidra.util.task.TaskMonitor;

public class ActionExecutor {

    private static final Gson gson = new Gson();

    public static void executeAction(String action, String argumentsJson, Program program, Address address)
            throws Exception {
        executeAction(action, argumentsJson, program, address, null, null, null);
    }

    public static void executeAction(String action, String argumentsJson, Program program, Address address,
                                     AnalysisDB analysisDB, String binaryId) throws Exception {
        executeAction(action, argumentsJson, program, address, analysisDB, binaryId, null);
    }

    /**
     * Execute an action and optionally record LLM renames for provenance tracking.
     *
     * Target resolution precedence:
     *   1. arguments._target_entry_address (round-trippable Address string, address-space-safe)
     *   2. address (caller-supplied cursor / fallback)
     *
     * Apply-time preconditions:
     *   - rename_function: target function must still be default-named (FUN_xxx/sub_xxx) unless
     *     arguments._target_func_name matches the current name.
     *   - rename_variable: current variable name must equal arguments.var_name (still-expected-old-name).
     *
     * Synthetic keys (prefixed with '_target_') are stripped before any persistence/logging.
     *
     * structRegistry (optional): when non-null, auto_create_struct routes new structures through
     * exact-signature deduplication to avoid duplicating already-registered struct layouts.
     */
    public static void executeAction(String action, String argumentsJson, Program program, Address address,
                                     AnalysisDB analysisDB, String binaryId,
                                     StructSignatureRegistry structRegistry) throws Exception {
        JsonObject arguments = gson.fromJson(argumentsJson, JsonObject.class);

        Function target = resolveTargetFunction(program, arguments, address);
        Address effectiveAddress = target != null ? target.getEntryPoint() : address;

        int transaction = program.startTransaction("Execute " + action);
        boolean success = false;

        try {
            switch (action) {
                case "rename_function":
                    executeFunctionRename(arguments, program, effectiveAddress);
                    if (analysisDB != null && binaryId != null && target != null) {
                        String newName = arguments.get("new_name").getAsString().strip();
                        analysisDB.recordLlmRename(binaryId, target.getEntryPoint().getOffset(), "function", newName);
                    }
                    break;
                case "rename_variable":
                    executeVariableRename(arguments, program, effectiveAddress);
                    if (analysisDB != null && binaryId != null && target != null) {
                        String newName = arguments.get("new_name").getAsString().strip();
                        analysisDB.recordLlmRename(binaryId, target.getEntryPoint().getOffset(), "variable", newName);
                    }
                    break;
                case "retype_variable":
                    executeVariableRetype(arguments, program, effectiveAddress);
                    break;
                case "auto_create_struct":
                    executeAutoCreateStruct(arguments, program, effectiveAddress, structRegistry);
                    break;
                case "set_signature":
                    executeSetSignature(arguments, program, effectiveAddress);
                    break;
                default:
                    throw new InvalidInputException("Unknown action: " + action);
            }
            success = true;
        } finally {
            program.endTransaction(transaction, success);
        }
    }

    /**
     * Resolve target function using synthetic _target_* keys when present, falling back to cursor.
     *
     * Fail-closed policy: if _target_entry_address is present but cannot be resolved to a function
     * in this program (stale target, wrong program loaded), we throw rather than silently falling
     * back to the cursor — otherwise an action generated for function A could be applied to
     * whichever function the user happens to be viewing.
     *
     * Cursor fallback is only used when no synthetic target was specified at all.
     */
    public static Function resolveTargetFunction(Program program, JsonObject arguments, Address cursor)
            throws InvalidInputException {
        if (program == null) {
            return null;
        }
        FunctionManager fm = program.getFunctionManager();

        if (arguments != null && arguments.has(ActionParser.KEY_TARGET_ENTRY_ADDRESS)
                && !arguments.get(ActionParser.KEY_TARGET_ENTRY_ADDRESS).isJsonNull()) {
            String addrStr;
            try {
                addrStr = arguments.get(ActionParser.KEY_TARGET_ENTRY_ADDRESS).getAsString().strip();
            } catch (Exception e) {
                throw new InvalidInputException("Malformed " + ActionParser.KEY_TARGET_ENTRY_ADDRESS
                    + ": " + e.getMessage());
            }
            if (!addrStr.isEmpty()) {
                Address entry;
                try {
                    AddressFactory factory = program.getAddressFactory();
                    entry = factory.getAddress(addrStr);
                } catch (Exception e) {
                    throw new InvalidInputException("Invalid target entry address '" + addrStr
                        + "': " + e.getMessage());
                }
                if (entry == null) {
                    throw new InvalidInputException("Unparseable target entry address: " + addrStr);
                }
                Function f = fm.getFunctionAt(entry);
                if (f == null) {
                    throw new InvalidInputException("No function at target entry address "
                        + addrStr + " (stale target or wrong program loaded)");
                }
                return f;
            }
        }

        if (cursor != null) {
            Function f = fm.getFunctionContaining(cursor);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /**
     * Strip synthetic _target_* keys from arguments before passing to transcript/export/logging paths.
     * Safe to call on any JsonObject; returns a defensive copy with synthetic keys removed.
     */
    public static JsonObject stripSyntheticKeys(JsonObject arguments) {
        if (arguments == null) {
            return null;
        }
        JsonObject copy = arguments.deepCopy();
        copy.remove(ActionParser.KEY_TARGET_ENTRY_ADDRESS);
        copy.remove(ActionParser.KEY_TARGET_FUNC_NAME);
        return copy;
    }

    private static String requireString(JsonObject args, String key) throws InvalidInputException {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            throw new InvalidInputException("Missing required argument: " + key);
        }
        String value;
        try {
            value = args.get(key).getAsString();
        } catch (Exception e) {
            throw new InvalidInputException("Argument '" + key + "' is not a string: " + e.getMessage());
        }
        if (value == null) {
            throw new InvalidInputException("Argument '" + key + "' is null");
        }
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new InvalidInputException("Argument '" + key + "' is empty");
        }
        return stripped;
    }

    private static void executeFunctionRename(JsonObject arguments, Program program, Address address)
            throws InvalidInputException, DuplicateNameException {
        String newName = requireString(arguments, "new_name");
        Function function = getFunctionAtAddress(program, address);

        if (arguments.has(ActionParser.KEY_TARGET_FUNC_NAME)
                && !arguments.get(ActionParser.KEY_TARGET_FUNC_NAME).isJsonNull()) {
            String expectedOldName = arguments.get(ActionParser.KEY_TARGET_FUNC_NAME).getAsString().strip();
            if (!expectedOldName.isEmpty() && !expectedOldName.equals(function.getName())) {
                throw new InvalidInputException(
                    "rename_function precondition failed: expected current name '" + expectedOldName
                    + "' but function is now '" + function.getName() + "' at " + address);
            }
        }

        function.setName(newName, SourceType.USER_DEFINED);
    }

    private static void executeVariableRename(JsonObject arguments, Program program, Address address)
            throws InvalidInputException, DuplicateNameException {
        String varName = requireString(arguments, "var_name");
        String newName = requireString(arguments, "new_name");

        Function function = getFunctionAtAddress(program, address);
        Variable variable = findVariable(function, varName);

        variable.setName(newName, SourceType.USER_DEFINED);
    }

    private static void executeVariableRetype(JsonObject arguments, Program program, Address address) 
            throws Exception {
        String varName = requireString(arguments, "var_name");
        String newTypeStr = requireString(arguments, "new_type");
        
        Function function = getFunctionAtAddress(program, address);
        Variable variable = findVariable(function, varName);
        
        DataType newType = parseDataType(newTypeStr, program.getDataTypeManager(), program);
        if (newType == null) {
            throw new InvalidInputException("Failed to parse data type: " + newTypeStr);
        }
        
        variable.setDataType(newType, true, true, SourceType.USER_DEFINED);
    }

    private static void executeAutoCreateStruct(JsonObject arguments, Program program, Address address,
            StructSignatureRegistry structRegistry) throws Exception {
        String varName = requireString(arguments, "var_name");
        Function function = getFunctionAtAddress(program, address);
        
        DecompInterface decompiler = new DecompInterface();
        try {
            setupDecompiler(decompiler, program);
            DecompileResults results = decompiler.decompileFunction(function, 30, TaskMonitor.DUMMY);
            
            if (!results.decompileCompleted()) {
                throw new Exception("Decompilation failed for function: " + function.getName());
            }

            HighFunction highFunction = results.getHighFunction();
            HighVariable highVar = findHighVariable(highFunction, varName);
            
            if (highVar == null) {
                throw new InvalidInputException("Variable not found: " + varName);
            }

            createAndApplyStructure(program, function, highVar, decompiler, structRegistry);
            
        } finally {
            decompiler.dispose();
        }
    }

    private static void executeSetSignature(JsonObject arguments, Program program, Address address)
            throws Exception {
        Function function = getFunctionAtAddress(program, address);

        String newReturnTypeStr = requireString(arguments, "new_return_type");
        DataType returnDT = parseDataType(newReturnTypeStr, program.getDataTypeManager(), program);
        if (returnDT == null) {
            throw new InvalidInputException("Failed to parse return type: " + newReturnTypeStr);
        }

        // Accept either nested parameters array OR flattened parameter_names/parameter_types arrays
        List<Parameter> newParams = new ArrayList<>();
        Parameter[] currentParams = function.getParameters();

        if (arguments.has("parameters") && arguments.get("parameters").isJsonArray()) {
            com.google.gson.JsonArray paramsArr = arguments.getAsJsonArray("parameters");
            if (paramsArr.size() != currentParams.length) {
                throw new InvalidInputException("set_signature parameter count " + paramsArr.size()
                    + " does not match function parameter count " + currentParams.length
                    + " for " + function.getName());
            }
            for (int i = 0; i < paramsArr.size(); i++) {
                com.google.gson.JsonObject pObj = paramsArr.get(i).getAsJsonObject();
                if (!pObj.has("name") || !pObj.has("type")) {
                    throw new InvalidInputException("parameters[" + i + "] missing name or type");
                }
                String pName = pObj.get("name").getAsString().strip();
                String pTypeStr = pObj.get("type").getAsString().strip();
                if (pName.isEmpty() || pTypeStr.isEmpty()) {
                    throw new InvalidInputException("parameters[" + i + "] has empty name or type");
                }
                DataType pType = parseDataType(pTypeStr, program.getDataTypeManager(), program);
                if (pType == null) {
                    throw new InvalidInputException("Failed to parse parameter type: " + pTypeStr);
                }
                newParams.add(new ghidra.program.model.listing.ParameterImpl(
                    pName, pType, program, SourceType.USER_DEFINED));
            }
        } else if (arguments.has("parameter_names") && arguments.has("parameter_types")
                && arguments.get("parameter_names").isJsonArray() && arguments.get("parameter_types").isJsonArray()) {
            com.google.gson.JsonArray namesArr = arguments.getAsJsonArray("parameter_names");
            com.google.gson.JsonArray typesArr = arguments.getAsJsonArray("parameter_types");
            if (namesArr.size() != typesArr.size() || namesArr.size() != currentParams.length) {
                throw new InvalidInputException("parameter_names/types count mismatch or count does not match function parameters");
            }
            for (int i = 0; i < namesArr.size(); i++) {
                String pName = namesArr.get(i).getAsString().strip();
                String pTypeStr = typesArr.get(i).getAsString().strip();
                if (pName.isEmpty() || pTypeStr.isEmpty()) {
                    throw new InvalidInputException("parameter_names/parameter_types contains empty entry at index " + i);
                }
                DataType pType = parseDataType(pTypeStr, program.getDataTypeManager(), program);
                if (pType == null) {
                    throw new InvalidInputException("Failed to parse parameter type: " + pTypeStr);
                }
                newParams.add(new ghidra.program.model.listing.ParameterImpl(
                    pName, pType, program, SourceType.USER_DEFINED));
            }
        } else {
            throw new InvalidInputException("Missing 'parameters' array or flattened 'parameter_names'/'parameter_types'");
        }

        function.updateFunction(
            function.getCallingConventionName(),
            new ghidra.program.model.listing.ReturnParameterImpl(returnDT, program),
            newParams,
            FunctionUpdateType.DYNAMIC_STORAGE_FORMAL_PARAMS,
            true,
            SourceType.USER_DEFINED
        );
    }

    private static Function getFunctionAtAddress(Program program, Address address) 
            throws InvalidInputException {
        Function function = program.getFunctionManager().getFunctionContaining(address);
        if (function == null) {
            throw new InvalidInputException("No function at address: " + address);
        }
        return function;
    }

    private static Variable findVariable(Function function, String varName) 
            throws InvalidInputException {
        commitLocalNames(function.getProgram(), function);

        List<Variable> allVars = new ArrayList<>();
        allVars.addAll(Arrays.asList(function.getAllVariables()));
        allVars.addAll(Arrays.asList(function.getParameters()));
        
        for (Variable var : allVars) {
            if (var.getName().equals(varName)) {
                return var;
            }
        }
        
        throw new InvalidInputException("Variable not found: " + varName);
    }

    private static DataType parseDataType(String typeStr, DataTypeManager dtm, Program program) 
            throws InvalidInputException, CancelledException, InvalidDataTypeException {
        DataType dataType = dtm.getDataType(new CategoryPath("/"), typeStr);
        
        if (dataType == null) {
            DataTypeParser parser = new DataTypeParser(dtm, program.getDataTypeManager(), null, DataTypeParser.AllowedDataTypes.ALL);
            dataType = parser.parse(typeStr);
        }
        
        return dataType;
    }

    private static void setupDecompiler(DecompInterface decompiler, Program program) {
        DecompileOptions options = new DecompileOptions();
        options.grabFromProgram(program);
        decompiler.setOptions(options);
        decompiler.openProgram(program);
    }

    private static HighVariable findHighVariable(HighFunction highFunction, String varName) {
        var symbols = highFunction.getLocalSymbolMap().getSymbols();
        while (symbols.hasNext()) {
            HighSymbol sym = symbols.next();
            if (sym.getName().equals(varName)) {
                return sym.getHighVariable();
            }
        }
        return null;
    }

    private static void createAndApplyStructure(Program program, Function function, 
            HighVariable highVar, DecompInterface decompiler,
            StructSignatureRegistry structRegistry) throws Exception {
        
        FillOutStructureHelper fillHelper = new FillOutStructureHelper(program, TaskMonitor.DUMMY);
        Structure structDT = fillHelper.processStructure(highVar, function, false, true, decompiler);
        
        if (structDT == null) {
            throw new Exception("Failed to create structure");
        }

        Structure stored;
        if (structRegistry != null) {
            stored = structRegistry.registerOrReuse(structDT);
            if (stored == null) {
                DataTypeManager dtm = program.getDataTypeManager();
                stored = (Structure) dtm.addDataType(structDT, DataTypeConflictHandler.DEFAULT_HANDLER);
            }
        } else {
            DataTypeManager dtm = program.getDataTypeManager();
            stored = (Structure) dtm.addDataType(structDT, DataTypeConflictHandler.DEFAULT_HANDLER);
        }
        PointerDataType ptrStruct = new PointerDataType(stored);

        Variable var = findVariable(function, highVar.getSymbol().getName());
        
        if (var instanceof ghidra.program.model.listing.AutoParameterImpl) {
            updateFunctionParameter(function, var.getName(), ptrStruct);
        } else {
            HighFunctionDBUtil.updateDBVariable(highVar.getSymbol(), null, ptrStruct, SourceType.USER_DEFINED);
        }
    }

    private static void updateFunctionParameter(Function function, String paramName, 
            DataType newType) throws InvalidInputException, DuplicateNameException {
        
        Parameter[] parameters = function.getParameters();
        Parameter[] newParams = new Parameter[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getName().equals(paramName)) {
                newParams[i] = new ghidra.program.model.listing.ParameterImpl(
                    parameters[i].getName(),
                    newType,
                    parameters[i].getVariableStorage(),
                    function.getProgram(),
                    SourceType.USER_DEFINED
                );
            } else {
                newParams[i] = parameters[i];
            }
        }

        function.updateFunction(
            function.getCallingConventionName(),
            null,
            FunctionUpdateType.CUSTOM_STORAGE,
            true,
            SourceType.USER_DEFINED,
            newParams
        );
    }

    public static void commitLocalNames(Program program, Function function) {
        DecompInterface ifc = new DecompInterface();
        ifc.openProgram(program);
        
        DecompileResults res = ifc.decompileFunction(function, 30, null);
        if (res.decompileCompleted()) {
            try {
                function.setName(function.getName(), SourceType.USER_DEFINED);
                HighFunction hf = res.getHighFunction();
                HighFunctionDBUtil.commitLocalNamesToDatabase(hf, SourceType.ANALYSIS);
                HighFunctionDBUtil.commitParamsToDatabase(hf, true, null, SourceType.ANALYSIS);
            } catch (Exception e) {
                Msg.error(ActionExecutor.class, "Error committing local names: " + e.getMessage());
            }
        }
        
        ifc.closeProgram();
    }
}
