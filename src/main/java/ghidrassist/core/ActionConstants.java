package ghidrassist.core;

import java.util.*;

@SuppressWarnings("unchecked")  // Intentional use of generic varargs for function template creation
public class ActionConstants {

    public static final double AUTO_APPLY_CONFIDENCE = 0.85;
    public static final int GLOBAL_MAX_CANDIDATES = 0;
    public static final int GLOBAL_MAX_CODE_CHARS = 6000;
    public static final int GLOBAL_MAX_CONTEXT_CHARS = 3000;
    public static final int GLOBAL_MAX_STRINGS = 12;
    public static final int GLOBAL_MAX_NAMED_CALLEES = 10;
    public static final int GLOBAL_LLM_TIMEOUT_SECONDS = 120;
    public static final double RETYPE_CONFIDENCE_FLOOR = 1.0;

    public static final List<Map<String, Object>> FN_TEMPLATES = Arrays.asList(
        createFunctionTemplate(
            "rename_function",
            "Rename a function",
            createParameters(
                createParameter("func_name", "string", "The current name of the function being renamed (e.g., FUN_00401234, sub_40001234)."),
                createParameter("new_name", "string", "The new name for the function. (e.g., recv_data)"),
                createParameter("confidence", "number", "Confidence in this suggestion, 0.0 to 1.0. Only rate >= 0.85 when strongly corroborated by strings, APIs, or callee names.")
            )
        ),
        createFunctionTemplate(
            "rename_variable",
            "Rename a variable within a function",
            createParameters(
                createParameter("func_name", "string", "The name of the function containing the variable. (e.g., sub_40001234)"),
                createParameter("var_name", "string", "The current name of the variable. (e.g., var_20)"),
                createParameter("new_name", "string", "The new name for the variable. (e.g., recv_buf)"),
                createParameter("confidence", "number", "Confidence in this suggestion, 0.0 to 1.0.")
            )
        ),
        createFunctionTemplate(
            "retype_variable",
            "Set a variable data type within a function",
            createParameters(
                createParameter("func_name", "string", "The name of the function containing the variable. (e.g., sub_40001234)"),
                createParameter("var_name", "string", "The current name of the variable. (e.g., rax_12)"),
                createParameter("new_type", "string", "The new type for the variable. (e.g., int32_t)"),
                createParameter("confidence", "number", "Confidence in this suggestion, 0.0 to 1.0.")
            )
        ),
        createFunctionTemplate(
            "auto_create_struct",
            "Automatically create a structure datatype from a variable given its offset uses in a given function.",
            createParameters(
                createParameter("func_name", "string", "The name of the function containing the variable. (e.g., sub_40001234)"),
                createParameter("var_name", "string", "The current name of the variable. (e.g., rax_12)"),
                createParameter("confidence", "number", "Confidence in this suggestion, 0.0 to 1.0.")
            )
        ),
        createFunctionTemplate(
            "set_signature",
            "Set the full signature of a function (return type, parameter names, and parameter types) in a single atomic call.",
            createSignatureParameters()
        )
    );

    public static final Map<String, String> ACTION_PROMPTS = new HashMap<>();

    // Prompts used by global binary-wide auto-analyze. Ask for one best suggestion per target,
    // strict corroboration, and emit confidence. Params-only for rename_variable; no retype_variable.
    public static final Map<String, String> GLOBAL_ACTION_PROMPTS = new HashMap<>();

    static {
        ACTION_PROMPTS.put("rename_function",
            "Analyze this decompiled function and suggest better names:\n```\n{code}\n```\n" +
            "Consider the code functionality, strings, API calls, and log parameters.\n" +
            "For C++ methods, prefer Class::Method naming. Otherwise use descriptive procedural names.\n" +
            "Call the rename_function tool 3 times with your best name suggestions."
        );
        ACTION_PROMPTS.put("rename_variable",
            "Analyze this decompiled function and suggest better variable names:\n```\n{code}\n```\n" +
            "Consider the code functionality, how variables are used, and any contextual hints.\n" +
            "Call the rename_variable tool for each variable that would benefit from a clearer name."
        );
        ACTION_PROMPTS.put("retype_variable",
            "Analyze this decompiled function and suggest better variable types:\n```\n{code}\n```\n" +
            "Consider how variables are used, pointer arithmetic, and common type patterns.\n" +
            "Call the retype_variable tool for each variable that would benefit from a more accurate type."
        );
        ACTION_PROMPTS.put("auto_create_struct",
            "Analyze this decompiled function for structure/class usage:\n```\n{code}\n```\n" +
            "Look for variables with offset access patterns like `*(ptr + 0xc)` or field-like usage.\n" +
            "Call the auto_create_struct tool for each variable that appears to be a structure or class instance."
        );
        ACTION_PROMPTS.put("set_signature",
            "Analyze this decompiled function and determine its most likely complete signature:\n```\n{code}\n```\n" +
            "Infer the return type, each parameter's name, and each parameter's type from usage: API calls with " +
            "known signatures, arithmetic/dereference patterns, string/format usage, return-value consumers.\n" +
            "Use concrete Ghidra type strings (e.g. 'int32_t', 'char *', 'size_t', 'void *', 'MyStruct *').\n" +
            "Pass the exact current function name as func_name. Provide parameters in source order.\n" +
            "Call the set_signature tool exactly once."
        );

        GLOBAL_ACTION_PROMPTS.put("rename_function",
            "You are renaming an unnamed function in a binary during automated batch analysis.\n" +
            "Current name: {current_name}\n" +
            "Decompiled code:\n```\n{code}\n```\n" +
            "Additional context (callers, callees, referenced strings, API calls, summaries):\n{context}\n\n" +
            "Rules:\n" +
            "- Produce EXACTLY ONE best name suggestion. Do not propose alternatives.\n" +
            "- Pass the exact current name as func_name.\n" +
            "- Confidence must reflect corroboration strength:\n" +
            "  >= 0.85 only when at least one of: distinctive strings, named API calls, meaningful callee names, or strong semantic summary supports the name.\n" +
            "  < 0.85 when behavior is ambiguous, generic, or only weakly inferred.\n" +
            "- Prefer descriptive procedural names. For C++ vtables/methods use Class::Method.\n" +
            "- Never propose names that collide with well-known C/C++ stdlib or OS APIs unless the function truly wraps them.\n" +
            "Call the rename_function tool exactly once."
        );
        GLOBAL_ACTION_PROMPTS.put("rename_variable",
            "You are naming PARAMETERS of a function during automated batch analysis.\n" +
            "Function: {current_name}\n" +
            "Decompiled code:\n```\n{code}\n```\n" +
            "Additional context:\n{context}\n\n" +
            "Rules:\n" +
            "- Only rename PARAMETERS (formal arguments). Do NOT rename local variables.\n" +
            "- Only rename parameters whose current name is generic (param_1, param_2, a1, arg0, etc.).\n" +
            "- Pass the exact function name as func_name and the exact current parameter name as var_name.\n" +
            "- Confidence must reflect how strongly the parameter's purpose is evidenced by usage.\n" +
            "- Skip parameters you cannot confidently name.\n" +
            "Call the rename_variable tool once per parameter you are confident about."
        );
        GLOBAL_ACTION_PROMPTS.put("retype_variable",
            "You are retyping PARAMETERS of a function during automated batch analysis.\n" +
            "Function: {current_name}\n" +
            "Decompiled code:\n```\n{code}\n```\n" +
            "Additional context:\n{context}\n\n" +
            "Rules:\n" +
            "- Only retype PARAMETERS (formal arguments). NEVER retype local variables.\n" +
            "- Only propose a new type when it is UNAMBIGUOUSLY derivable from uses:\n" +
            "    * passed to a well-known typed API whose signature is known (e.g. strlen(const char*), memcpy(void*,void*,size_t)),\n" +
            "    * dereferenced at offsets consistent with an already-defined struct,\n" +
            "    * used in arithmetic with another variable whose type is already known with certainty.\n" +
            "- Pass the exact function name as func_name and exact current parameter name as var_name.\n" +
            "- Use a concrete Ghidra type string in new_type (e.g. 'char *', 'size_t', 'MyStruct *', 'int32_t').\n" +
            "- Confidence MUST be exactly 1.0 and you must be 100% certain. If less than 100% certain, DO NOT call the tool for that parameter.\n" +
            "- Skip every parameter you are not 100% certain about. Emit no proposal rather than a weak one.\n" +
            "Call retype_variable once per parameter you are 100% certain about, or not at all."
        );
        GLOBAL_ACTION_PROMPTS.put("auto_create_struct",
            "You are identifying candidate structure variables during automated batch analysis.\n" +
            "Function: {current_name}\n" +
            "Decompiled code:\n```\n{code}\n```\n" +
            "Additional context:\n{context}\n\n" +
            "Rules:\n" +
            "- Only flag pointer variables accessed at multiple distinct positive offsets consistent with struct fields.\n" +
            "- Pass the exact function name as func_name and the exact current variable name as var_name.\n" +
            "- Confidence must reflect how clearly the access pattern implies a struct.\n" +
            "Call the auto_create_struct tool once per candidate.\n" +
            "Do NOT call retype_variable in this mode."
        );
        GLOBAL_ACTION_PROMPTS.put("set_signature",
            "You are determining the complete signature of a function during automated batch analysis.\n" +
            "Function: {current_name}\n" +
            "Decompiled code:\n```\n{code}\n```\n" +
            "Additional context (callers, callees, referenced strings, API calls, summaries):\n{context}\n\n" +
            "Rules:\n" +
            "- Produce EXACTLY ONE signature proposal. Do not propose alternatives.\n" +
            "- Pass the exact current name as func_name.\n" +
            "- Infer new_return_type and every parameter's {name, type} from unambiguous evidence: typed API calls, " +
            "  dereference/offset patterns, arithmetic with known-typed operands, format strings, return-value consumers.\n" +
            "- Provide parameters in source order. Parameter count must match the function's current parameter count.\n" +
            "- Parameter names must be descriptive (not param_1, a1, argX). Parameter types must be concrete Ghidra types.\n" +
            "- Confidence MUST be exactly 1.0 and you must be 100% certain about the ENTIRE signature " +
            "  (return + every parameter). If less than 100% certain about any part, DO NOT call the tool.\n" +
            "Call set_signature exactly once, or not at all."
        );
    }

    /**
     * Parameters schema for set_signature. Uses a nested array-of-objects for `parameters`
     * which the generic createParameters helper cannot express, so this is built inline.
     */
    private static Map<String, Object> createSignatureParameters() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("func_name", Map.of(
            "type", "string",
            "description", "The current name of the function whose signature is being set (e.g., FUN_00401234, sub_40001234)."
        ));
        properties.put("new_return_type", Map.of(
            "type", "string",
            "description", "The new return type as a concrete Ghidra type string (e.g., 'int32_t', 'void', 'char *', 'size_t')."
        ));

        Map<String, Object> paramItem = new HashMap<>();
        paramItem.put("type", "object");
        Map<String, Object> paramItemProps = new LinkedHashMap<>();
        paramItemProps.put("name", Map.of(
            "type", "string",
            "description", "Parameter name (descriptive, not param_1/a1/argX)."
        ));
        paramItemProps.put("type", Map.of(
            "type", "string",
            "description", "Parameter type as a concrete Ghidra type string."
        ));
        paramItem.put("properties", paramItemProps);
        paramItem.put("required", Arrays.asList("name", "type"));

        Map<String, Object> paramsArray = new HashMap<>();
        paramsArray.put("type", "array");
        paramsArray.put("description", "Parameters in source order. Count must match the function's current parameter count.");
        paramsArray.put("items", paramItem);
        properties.put("parameters", paramsArray);

        // Backwards-compatible flattened schema: some providers (Gemini OpenAI-compatible)
        // can be stricter about nested arrays of objects. Provide optional parallel arrays
        // parameter_names and parameter_types as an alternative way to express the same data.
        Map<String, Object> namesArray = new HashMap<>();
        namesArray.put("type", "array");
        namesArray.put("description", "Parameter names in source order (flattened alternative to 'parameters').");
        namesArray.put("items", Map.of("type", "string"));
        properties.put("parameter_names", namesArray);

        Map<String, Object> typesArray = new HashMap<>();
        typesArray.put("type", "array");
        typesArray.put("description", "Parameter types in source order (flattened alternative to 'parameters').");
        typesArray.put("items", Map.of("type", "string"));
        properties.put("parameter_types", typesArray);

        properties.put("confidence", Map.of(
            "type", "number",
            "description", "Confidence in this signature, 0.0 to 1.0. Must be exactly 1.0 to be eligible for auto-apply."
        ));

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("func_name", "new_return_type", "parameters", "confidence"));
        return schema;
    }

    // Helper methods for creating function templates
    private static Map<String, Object> createFunctionTemplate(String name, String description, Map<String, Object> parameters) {
        Map<String, Object> functionMap = new HashMap<>();
        functionMap.put("name", name);
        functionMap.put("description", description);
        functionMap.put("parameters", parameters);
        
        Map<String, Object> template = new HashMap<>();
        template.put("type", "function");
        template.put("function", functionMap);
        return template;
    }

    private static Map<String, Object> createParameters(Map<String, Object>... parameters) {
        Map<String, Object> parametersMap = new HashMap<>();
        parametersMap.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();
        
        for (Map<String, Object> param : parameters) {
            String name = (String) param.get("name");
            properties.put(name, param);
            required.add(name);
        }
        
        parametersMap.put("properties", properties);
        parametersMap.put("required", required);
        return parametersMap;
    }

    private static Map<String, Object> createParameter(String name, String type, String description) {
        Map<String, Object> param = new HashMap<>();
        param.put("name", name);
        param.put("type", type);
        param.put("description", description);
        return param;
    }
}
