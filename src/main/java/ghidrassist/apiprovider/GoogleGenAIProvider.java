package ghidrassist.apiprovider;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import ghidrassist.apiprovider.exceptions.ResponseException;
import ghidrassist.apiprovider.exceptions.StreamCancelledException;
import ghidrassist.apiprovider.exceptions.NetworkException;
import ghidrassist.LlmApi.LlmResponseHandler;
import java.io.IOException;
import java.io.StringReader;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import ghidrassist.apiprovider.exceptions.APIProviderException;
import com.google.gson.JsonArray;
import ghidrassist.apiprovider.ChatMessage;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Google GenAI API Provider.
 * Extends OpenAIPlatformApiProvider to reuse HTTP logic
 * while handling Google-specific endpoint, model naming, and payload requirements.
 */
public class GoogleGenAIProvider extends OpenAIPlatformApiProvider {

    public GoogleGenAIProvider(String name, String model, Integer maxTokens, String url, String key,
                              boolean disableTlsVerification, boolean bypassProxy, Integer timeout) {
        super(name, model, maxTokens, url, key, disableTlsVerification, bypassProxy, timeout);
        this.type = ProviderType.GOOGLE_GENAI_API;
    }

    @Override
    protected okhttp3.OkHttpClient buildClient() {
        try {
            okhttp3.OkHttpClient.Builder builder = configureClientBuilder(new okhttp3.OkHttpClient.Builder())
                .connectTimeout(super.timeout)
                .readTimeout(super.timeout)
                .writeTimeout(super.timeout)
                .retryOnConnectionFailure(true)
                .addInterceptor(chain -> {
                    Request originalRequest = chain.request();
                    Request.Builder requestBuilder = originalRequest.newBuilder()
                        .header("Content-Type", "application/json");
                    
                    if (key != null && !key.isEmpty()) {
                        requestBuilder.header("x-goog-api-key", key);
                    }
                    
                    if (!originalRequest.method().equals("GET")) {
                        requestBuilder.header("Accept", "application/json");
                    }
                    
                    return chain.proceed(requestBuilder.build());
                });

            if (disableTlsVerification) {
                javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[]{};
                        }
                    }
                };

                javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                builder.sslSocketFactory(sslContext.getSocketFactory(), (javax.net.ssl.X509TrustManager) trustAllCerts[0])
                       .hostnameVerifier((hostname, session) -> true);
            }

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Google GenAI HTTP client: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> getAvailableModels() throws APIProviderException {
        // Fetch models using OpenAI-compatible logic in super class
        List<String> models = super.getAvailableModels();
        
        // Ensure all model IDs have the "models/" prefix expected by Google GenAI endpoints
        return models.stream()
            .map(GoogleGenAIProvider::ensureModelPathPrefix)
            .collect(Collectors.toList());
    }

    @Override
    protected List<String> getModelsEndpointCandidates() {
        List<String> candidates = new ArrayList<>();
        String baseUrl = this.url;
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        String requestUrl = baseUrl + "models";
        if (key != null && !key.isEmpty()) {
            requestUrl += "?key=" + key;
        }
        candidates.add(requestUrl);
        return candidates;
    }

    @Override
    protected JsonObject buildChatCompletionPayload(List<ChatMessage> messages, boolean stream) {
        JsonObject payload = new JsonObject();
        
        JsonArray contents = new JsonArray();
        StringBuilder systemInstructionBuilder = new StringBuilder();
        
        String currentRole = null;
        JsonArray currentParts = null;
        JsonObject currentContent = null;
        
        for (ChatMessage msg : messages) {
            String role = msg.getRole();
            
            if ("system".equals(role)) {
                if (msg.getContent() != null) {
                    if (systemInstructionBuilder.length() > 0) {
                        systemInstructionBuilder.append("\n");
                    }
                    systemInstructionBuilder.append(msg.getContent());
                }
                continue;
            }
            
            String targetRole = "assistant".equals(role) ? "model" : "user";
            
            if (!targetRole.equals(currentRole)) {
                if (currentContent != null) {
                    currentContent.add("parts", currentParts);
                    contents.add(currentContent);
                }
                currentRole = targetRole;
                currentContent = new JsonObject();
                currentContent.addProperty("role", currentRole);
                currentParts = new JsonArray();
            }

            if (msg.getToolCalls() != null && msg.getToolCalls().size() > 0) {
                for (JsonElement tcElement : msg.getToolCalls()) {
                    if (tcElement.isJsonObject() && tcElement.getAsJsonObject().has("function")) {
                        JsonObject tcObj = tcElement.getAsJsonObject();
                        JsonObject function = tcObj.getAsJsonObject("function");
                        JsonObject functionCall = new JsonObject();
                        if (function.has("name")) {
                            functionCall.addProperty("name", function.get("name").getAsString());
                        }
                        if (tcObj.has("id")) {
                            functionCall.addProperty("id", tcObj.get("id").getAsString());
                        }
                        if (function.has("arguments")) {
                            String argsStr = function.get("arguments").getAsString();
                            try {
                                functionCall.add("args", com.google.gson.JsonParser.parseString(argsStr));
                            } catch (JsonSyntaxException e) {
                                functionCall.add("args", new JsonObject());
                            }
                        }
                        JsonObject callPart = new JsonObject();
                        callPart.add("functionCall", functionCall);
                        currentParts.add(callPart);
                    }
                }
            } else if (msg.getToolCallId() != null) {
                JsonObject functionResponse = new JsonObject();
                
                String funcName = "tool_result";
                String targetId = msg.getToolCallId();
                for (ChatMessage prevMsg : messages) {
                    if (prevMsg.getToolCalls() != null) {
                        for (JsonElement tcElement : prevMsg.getToolCalls()) {
                            if (tcElement.isJsonObject() && tcElement.getAsJsonObject().has("id")) {
                                String id = tcElement.getAsJsonObject().get("id").getAsString();
                                if (targetId.equals(id)) {
                                    if (tcElement.getAsJsonObject().has("function")) {
                                        JsonObject fn = tcElement.getAsJsonObject().getAsJsonObject("function");
                                        if (fn.has("name")) {
                                            funcName = fn.get("name").getAsString();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                functionResponse.addProperty("name", funcName); 
                functionResponse.addProperty("id", msg.getToolCallId());
                
                JsonObject responseContent = new JsonObject();
                if (msg.getContent() != null) {
                    try {
                        responseContent = com.google.gson.JsonParser.parseString(msg.getContent()).getAsJsonObject();
                    } catch (JsonSyntaxException e) {
                        responseContent.addProperty("result", msg.getContent());
                    }
                } else {
                    responseContent.addProperty("result", "success");
                }
                functionResponse.add("response", responseContent);
                JsonObject responsePart = new JsonObject();
                responsePart.add("functionResponse", functionResponse);
                currentParts.add(responsePart);
            } else {
                JsonObject part = new JsonObject();
                if (msg.getContent() != null) {
                    part.addProperty("text", msg.getContent());
                } else {
                    part.addProperty("text", "");
                }
                currentParts.add(part);
            }
        }
        
        if (currentContent != null) {
            currentContent.add("parts", currentParts);
            contents.add(currentContent);
        }
        
        if (systemInstructionBuilder.length() > 0) {
            JsonObject systemInstruction = new JsonObject();
            JsonArray sysParts = new JsonArray();
            JsonObject sysPart = new JsonObject();
            sysPart.addProperty("text", systemInstructionBuilder.toString());
            sysParts.add(sysPart);
            systemInstruction.add("parts", sysParts);
            payload.add("systemInstruction", systemInstruction);
        }
        
        payload.add("contents", contents);

        JsonObject generationConfig = new JsonObject();
        if (super.getMaxTokens() != null) {
            generationConfig.addProperty("maxOutputTokens", super.getMaxTokens());
        }
        
        ReasoningConfig reasoning = getReasoningConfig();
        if (reasoning != null && reasoning.isEnabled()) {
            JsonObject thinkingConfig = new JsonObject();
            thinkingConfig.addProperty("includeThoughts", true);
            generationConfig.add("thinkingConfig", thinkingConfig);
        }
        
        payload.add("generationConfig", generationConfig);

        if (stream) {
            payload.addProperty("_is_stream", true);
        }

        return payload;
    }

    @Override
    protected Request buildPostRequestForEndpoint(String endpoint, JsonObject payload) {
        String method = "generateContent";
        if (payload.has("_is_stream") && payload.get("_is_stream").getAsBoolean()) {
            method = "streamGenerateContent?alt=sse";
            payload.remove("_is_stream");
        }
        
        String path;
        if (endpoint.equals(OPENAI_MODELS_ENDPOINT)) {
            path = "models";
        } else {
            String modelName = payload.has("model") ? payload.get("model").getAsString() : this.model;
            modelName = ensureModelPathPrefix(modelName);
            path = modelName + ":" + method;
        }

        String baseUrl = this.url;
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }

        String requestUrl = baseUrl + path;
        if (key != null && !key.isEmpty()) {
            requestUrl += (requestUrl.contains("?") ? "&" : "?") + "key=" + key;
        }

        return new Request.Builder()
            .url(requestUrl)
            .post(RequestBody.create(gson.toJson(payload), JSON))
            .build();
    }

    /**
     * Google GenAI endpoints (like :generateContent) usually require the model name
     * to be prefixed with "models/".
     */
    protected static String ensureModelPathPrefix(String model) {
        if (model == null) return null;
        String m = model.trim();
        while (m.startsWith("/")) {
            m = m.substring(1);
        }
        if (!m.startsWith("models/")) {
            m = "models/" + m;
        }
        return m;
    }
    
    private JsonElement stripUnsupportedSchemaKeys(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return element;
        }

        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            JsonObject out = new JsonObject();
            
            for (java.util.Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                String key = entry.getKey();
                
                if ("additionalProperties".equals(key) || 
                    "additional_properties".equals(key) || 
                    "name".equals(key) ||
                    "title".equals(key) ||
                    "default".equals(key)) {
                    continue;
                }
                
                out.add(key, stripUnsupportedSchemaKeys(entry.getValue()));
            }
            return out;
        }

        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            JsonArray out = new JsonArray();
            for (JsonElement e : arr) {
                out.add(stripUnsupportedSchemaKeys(e));
            }
            return out;
        }

        return element;
    }

    @Override
    public String createChatCompletionWithFunctions(List<ChatMessage> messages, List<Map<String, Object>> functions) throws APIProviderException {
        return createChatCompletionWithFunctions(messages, functions, ToolChoiceMode.AUTO);
    }

    @Override
    public String createChatCompletionWithFunctions(List<ChatMessage> messages, List<Map<String, Object>> functions,
                                                    ToolChoiceMode toolChoiceMode) throws APIProviderException {
        JsonObject payload = buildChatCompletionPayload(messages, false);
        
        // Add tools (function declarations)
        if (functions != null && !functions.isEmpty()) {
            JsonArray functionDeclarations = new JsonArray();
            for (Map<String, Object> func : functions) {
                // OpenAI has {"type": "function", "function": {"name": ..., "description": ..., "parameters": ...}}
                // Gemini wants {"functionDeclarations": [{"name": ..., "description": ..., "parameters": ...}]}
                JsonObject funcObj = null;
                if (func.containsKey("type") && "function".equals(func.get("type")) && func.containsKey("function")) {
                    // Extract from OpenAI format
                    funcObj = gson.toJsonTree(func.get("function")).getAsJsonObject();
                } else if (func.containsKey("name")) {
                    // Already in flat format
                    funcObj = gson.toJsonTree(func).getAsJsonObject();
                }
                
                if (funcObj != null) {
                    if (funcObj.has("parameters")) {
                        funcObj.add("parameters", stripUnsupportedSchemaKeys(funcObj.get("parameters")));
                    }
                    functionDeclarations.add(funcObj);
                }
            }
            
            if (functionDeclarations.size() > 0) {
                JsonArray toolsArray = new JsonArray();
                JsonObject toolsObj = new JsonObject();
                toolsObj.add("functionDeclarations", functionDeclarations);
                toolsArray.add(toolsObj);
                payload.add("tools", toolsArray);
            }
            
            // ToolConfig (replaces tool_choice)
            if (toolChoiceMode != null) {
                JsonObject toolConfig = new JsonObject();
                JsonObject functionCallingConfig = new JsonObject();
                
                functionCallingConfig.addProperty("mode", toolChoiceMode.toGeminiFunctionCallingMode(messages));
                
                toolConfig.add("functionCallingConfig", functionCallingConfig);
                payload.add("toolConfig", toolConfig);
            }
        }

        Request request = buildPostRequestForEndpoint(OPENAI_CHAT_ENDPOINT, payload);

        try (Response response = executeWithRetry(request, "createChatCompletionWithFunctions")) {
            String responseBody = response.body().string();
            
            try {
                JsonObject responseObj = gson.fromJson(responseBody, JsonObject.class);
                
                // Extract tool calls from Gemini response format
                if (responseObj.has("candidates")) {
                    JsonArray candidates = responseObj.getAsJsonArray("candidates");
                    if (candidates.size() > 0) {
                        JsonObject candidate = candidates.get(0).getAsJsonObject();
                        if (candidate.has("content") && candidate.get("content").isJsonObject()) {
                            JsonObject content = candidate.getAsJsonObject("content");
                            if (content.has("parts") && content.get("parts").isJsonArray()) {
                                JsonArray parts = content.getAsJsonArray("parts");
                                
                                JsonArray toolCalls = new JsonArray();
                                for (int i = 0; i < parts.size(); i++) {
                                    JsonObject part = parts.get(i).getAsJsonObject();
                                    if (part.has("functionCall")) {
                                        JsonObject functionCall = part.getAsJsonObject("functionCall");
                                        
                                        JsonObject toolCall = new JsonObject();
                                        String tcId = functionCall.has("id") ? functionCall.get("id").getAsString() : "call_" + java.util.UUID.randomUUID().toString().substring(0, 8);
                                        toolCall.addProperty("id", tcId);
                                        toolCall.addProperty("type", "function");
                                        
                                        JsonObject function = new JsonObject();
                                        if (functionCall.has("name")) {
                                            function.addProperty("name", functionCall.get("name").getAsString());
                                        }
                                        if (functionCall.has("args")) {
                                            function.addProperty("arguments", gson.toJson(functionCall.get("args")));
                                        } else {
                                            function.addProperty("arguments", "{}");
                                        }
                                        
                                        toolCall.add("function", function);
                                        toolCalls.add(toolCall);
                                    }
                                }
                                
                                if (toolCalls.size() > 0) {
                                    return "{\"tool_calls\":" + toolCalls.toString() + "}";
                                }
                                
                                // If no tool calls, check for text content
                                for (int i = 0; i < parts.size(); i++) {
                                    JsonObject part = parts.get(i).getAsJsonObject();
                                    if (part.has("text") && !part.get("text").isJsonNull()) {
                                        return part.get("text").getAsString();
                                    }
                                }
                            }
                        }
                    }
                }
                
                return "{\"tool_calls\":[]}";
                
            } catch (JsonSyntaxException e) {
                throw new ResponseException(name, "createChatCompletionWithFunctions", 
                    ResponseException.ResponseErrorType.MALFORMED_JSON, e);
            }
        } catch (IOException e) {
            throw handleNetworkError(e, "createChatCompletionWithFunctions");
        }
    }

    @Override
    public String createChatCompletionWithFunctionsFullResponse(List<ChatMessage> messages, List<Map<String, Object>> functions) throws APIProviderException {
        return createChatCompletionWithFunctions(messages, functions, ToolChoiceMode.AUTO);
    }

    @Override
    public String createChatCompletionWithFunctionsFullResponse(List<ChatMessage> messages,
                                                                List<Map<String, Object>> functions,
                                                                ToolChoiceMode toolChoiceMode) throws APIProviderException {
        JsonObject payload = buildChatCompletionPayload(messages, false);
        
        // Add tools (function declarations)
        if (functions != null && !functions.isEmpty()) {
            JsonArray functionDeclarations = new JsonArray();
            for (Map<String, Object> func : functions) {
                JsonObject funcObj = null;
                if (func.containsKey("type") && "function".equals(func.get("type")) && func.containsKey("function")) {
                    funcObj = gson.toJsonTree(func.get("function")).getAsJsonObject();
                } else if (func.containsKey("name")) {
                    funcObj = gson.toJsonTree(func).getAsJsonObject();
                }
                
                if (funcObj != null) {
                    if (funcObj.has("parameters")) {
                        funcObj.add("parameters", stripUnsupportedSchemaKeys(funcObj.get("parameters")));
                    }
                    functionDeclarations.add(funcObj);
                }
            }
            
            if (functionDeclarations.size() > 0) {
                JsonArray toolsArray = new JsonArray();
                JsonObject toolsObj = new JsonObject();
                toolsObj.add("functionDeclarations", functionDeclarations);
                toolsArray.add(toolsObj);
                payload.add("tools", toolsArray);
            }
            
            // ToolConfig (replaces tool_choice)
            if (toolChoiceMode != null) {
                JsonObject toolConfig = new JsonObject();
                JsonObject functionCallingConfig = new JsonObject();
                
                functionCallingConfig.addProperty("mode", toolChoiceMode.toGeminiFunctionCallingMode(messages));
                
                toolConfig.add("functionCallingConfig", functionCallingConfig);
                payload.add("toolConfig", toolConfig);
            }
        }

        Request request = buildPostRequestForEndpoint(OPENAI_CHAT_ENDPOINT, payload);

        try (Response response = executeWithRetry(request, "createChatCompletionWithFunctionsFullResponse")) {
            String responseBody = response.body().string();
            
            try {
                JsonObject responseObj = gson.fromJson(responseBody, JsonObject.class);
                
                // Build OpenAI-compatible full response
                JsonObject openaiResponse = new JsonObject();
                JsonArray choices = new JsonArray();
                JsonObject choice = new JsonObject();
                JsonObject message = new JsonObject();
                message.addProperty("role", "assistant");
                
                String finishReason = "stop";
                JsonArray toolCalls = new JsonArray();
                StringBuilder textContent = new StringBuilder();
                
                // Extract tool calls from Gemini response format
                if (responseObj.has("candidates")) {
                    JsonArray candidates = responseObj.getAsJsonArray("candidates");
                    if (candidates.size() > 0) {
                        JsonObject candidate = candidates.get(0).getAsJsonObject();
                        
                        if (candidate.has("finishReason") && !candidate.get("finishReason").isJsonNull()) {
                            String gfr = candidate.get("finishReason").getAsString();
                            if ("STOP".equalsIgnoreCase(gfr)) {
                                finishReason = "stop";
                            } else {
                                finishReason = gfr.toLowerCase();
                            }
                        }
                        
                        if (candidate.has("content") && candidate.get("content").isJsonObject()) {
                            JsonObject content = candidate.getAsJsonObject("content");
                            if (content.has("parts") && content.get("parts").isJsonArray()) {
                                JsonArray parts = content.getAsJsonArray("parts");
                                
                                for (int i = 0; i < parts.size(); i++) {
                                    JsonObject part = parts.get(i).getAsJsonObject();
                                    
                                    if (part.has("text") && !part.get("text").isJsonNull()) {
                                        textContent.append(part.get("text").getAsString());
                                    }
                                    
                                    if (part.has("functionCall")) {
                                        JsonObject functionCall = part.getAsJsonObject("functionCall");
                                        
                                        JsonObject toolCall = new JsonObject();
                                        String tcId = functionCall.has("id") ? functionCall.get("id").getAsString() : "call_" + java.util.UUID.randomUUID().toString().substring(0, 8);
                                        toolCall.addProperty("id", tcId);
                                        toolCall.addProperty("type", "function");
                                        
                                        JsonObject function = new JsonObject();
                                        if (functionCall.has("name")) {
                                            function.addProperty("name", functionCall.get("name").getAsString());
                                        }
                                        if (functionCall.has("args")) {
                                            function.addProperty("arguments", gson.toJson(functionCall.get("args")));
                                        } else {
                                            function.addProperty("arguments", "{}");
                                        }
                                        
                                        toolCall.add("function", function);
                                        toolCalls.add(toolCall);
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (toolCalls.size() > 0) {
                    message.add("tool_calls", toolCalls);
                    finishReason = "tool_calls";
                    // If no text was returned alongside the tool call, ensure it's at least an empty string 
                    // or null depending on what OpenAI typically sends. We send empty string.
                    if (textContent.length() == 0) {
                        message.addProperty("content", "");
                    } else {
                        message.addProperty("content", textContent.toString());
                    }
                } else {
                    message.addProperty("content", textContent.toString());
                }
                
                choice.addProperty("finish_reason", finishReason);
                choice.add("message", message);
                choices.add(choice);
                
                openaiResponse.add("choices", choices);
                
                return openaiResponse.toString();
                
            } catch (JsonSyntaxException e) {
                throw new ResponseException(name, "createChatCompletionWithFunctionsFullResponse", 
                    ResponseException.ResponseErrorType.MALFORMED_JSON, e);
            }
        } catch (IOException e) {
            throw handleNetworkError(e, "createChatCompletionWithFunctionsFullResponse");
        }
    }

    @Override
    public String createChatCompletion(List<ChatMessage> messages) throws APIProviderException {
        JsonObject chatPayload = buildChatCompletionPayload(messages, false);
        
        Request request = buildPostRequestForEndpoint(OPENAI_CHAT_ENDPOINT, chatPayload);

        try (Response response = executeWithRetry(request, "createChatCompletion")) {
            String responseBody = response.body().string();
            try {
                JsonObject responseObj = gson.fromJson(responseBody, JsonObject.class);
                return extractContentFromGeminiResponse(responseObj);
            } catch (JsonSyntaxException e) {
                throw new ResponseException(name, "createChatCompletion", 
                    ResponseException.ResponseErrorType.MALFORMED_JSON, e);
            }
        } catch (IOException e) {
            throw handleNetworkError(e, "createChatCompletion");
        }
    }

    private String extractContentFromGeminiResponse(JsonObject responseObj) {
        if (!responseObj.has("candidates")) return "";
        JsonArray candidates = responseObj.getAsJsonArray("candidates");
        if (candidates.size() == 0) return "";
        
        JsonObject candidate = candidates.get(0).getAsJsonObject();
        if (candidate.has("content") && candidate.get("content").isJsonObject()) {
            JsonObject content = candidate.getAsJsonObject("content");
            if (content.has("parts") && content.get("parts").isJsonArray()) {
                JsonArray parts = content.getAsJsonArray("parts");
                if (parts.size() > 0) {
                    JsonObject part = parts.get(0).getAsJsonObject();
                    if (part.has("text") && !part.get("text").isJsonNull()) {
                        return part.get("text").getAsString();
                    }
                }
            }
        }
        return "";
    }
    @Override
    protected String extractDeltaContent(JsonObject chunk) {
        try {
            if (chunk.has("candidates")) {
                JsonArray candidates = chunk.getAsJsonArray("candidates");
                if (candidates != null && candidates.size() > 0) {
                    JsonObject candidate = candidates.get(0).getAsJsonObject();
                    if (candidate.has("content") && candidate.get("content").isJsonObject()) {
                        JsonObject content = candidate.getAsJsonObject("content");
                        if (content.has("parts") && content.get("parts").isJsonArray()) {
                            JsonArray parts = content.getAsJsonArray("parts");
                            if (parts != null && parts.size() > 0) {
                                JsonObject part = parts.get(0).getAsJsonObject();
                                if (part.has("text") && !part.get("text").isJsonNull()) {
                                    return part.get("text").getAsString();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    @Override
    public void streamChatCompletionWithFunctions(List<ChatMessage> messages, List<Map<String, Object>> functions, StreamingFunctionHandler handler) throws APIProviderException {
        streamChatCompletionWithFunctions(messages, functions, ToolChoiceMode.AUTO, handler);
    }

    @Override
    public void streamChatCompletionWithFunctions(List<ChatMessage> messages, List<Map<String, Object>> functions, ToolChoiceMode toolChoiceMode, StreamingFunctionHandler handler) throws APIProviderException {
        JsonObject payload = buildChatCompletionPayload(messages, true);
        
        if (functions != null && !functions.isEmpty()) {
            JsonArray functionDeclarations = new JsonArray();
            for (Map<String, Object> func : functions) {
                JsonObject funcObj = null;
                if (func.containsKey("type") && "function".equals(func.get("type")) && func.containsKey("function")) {
                    funcObj = gson.toJsonTree(func.get("function")).getAsJsonObject();
                } else if (func.containsKey("name")) {
                    funcObj = gson.toJsonTree(func).getAsJsonObject();
                }
                
                if (funcObj != null) {
                    if (funcObj.has("parameters")) {
                        funcObj.add("parameters", stripUnsupportedSchemaKeys(funcObj.get("parameters")));
                    }
                    functionDeclarations.add(funcObj);
                }
            }
            
            if (functionDeclarations.size() > 0) {
                JsonArray toolsArray = new JsonArray();
                JsonObject toolsObj = new JsonObject();
                toolsObj.add("functionDeclarations", functionDeclarations);
                toolsArray.add(toolsObj);
                payload.add("tools", toolsArray);
            }
            
            if (toolChoiceMode != null) {
                JsonObject toolConfig = new JsonObject();
                JsonObject functionCallingConfig = new JsonObject();
                functionCallingConfig.addProperty("mode", toolChoiceMode.toGeminiFunctionCallingMode(messages));
                toolConfig.add("functionCallingConfig", functionCallingConfig);
                payload.add("toolConfig", toolConfig);
            }
        }
        
        executeStreamingFunctionsWithRetry(payload, handler, "stream_chat_completion_with_functions", 0);
    }
    @Override
    protected void executeStreamingFunctionsWithRetry(JsonObject payload, StreamingFunctionHandler handler, String operation, int attemptNumber) {
        if (isCancelled) {
            handler.onError(new StreamCancelledException(name, operation, StreamCancelledException.CancellationReason.USER_REQUESTED));
            return;
        }

        Request request = buildPostRequestForEndpoint(OPENAI_CHAT_ENDPOINT, payload);

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (call.isCanceled()) {
                    handler.onError(new StreamCancelledException(name, operation, StreamCancelledException.CancellationReason.USER_REQUESTED, e));
                    return;
                }
                APIProviderException error = handleNetworkError(e, operation);
                handler.onError(error);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        handler.onError(handleHttpError(response, operation));
                        return;
                    }

                    if (responseBody == null) {
                        handler.onError(new ResponseException(name, operation, ResponseException.ResponseErrorType.EMPTY_RESPONSE));
                        return;
                    }

                    BufferedSource source = responseBody.source();
                    StringBuilder textBuilder = new StringBuilder();
                    java.util.List<ToolCall> toolCalls = new java.util.ArrayList<>();
                    String finishReason = "stop";

                    try {
                        while (!source.exhausted() && !isCancelled && handler.shouldContinue()) {
                            String line = source.readUtf8Line();
                            if (line == null || line.isEmpty()) continue;

                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if (data.equals("[DONE]")) {
                                    handler.onStreamComplete(finishReason, textBuilder.toString(), toolCalls);
                                    return;
                                }

                                try {
                                    JsonObject chunk = gson.fromJson(data, JsonObject.class);
                                    
                                    if (chunk.has("candidates")) {
                                        JsonArray candidates = chunk.getAsJsonArray("candidates");
                                        if (candidates.size() > 0) {
                                            JsonObject candidate = candidates.get(0).getAsJsonObject();
                                            
                                            if (candidate.has("finishReason") && !candidate.get("finishReason").isJsonNull()) {
                                                finishReason = candidate.get("finishReason").getAsString();
                                            }
                                            
                                            if (candidate.has("content") && candidate.get("content").isJsonObject()) {
                                                JsonObject content = candidate.getAsJsonObject("content");
                                                if (content.has("parts") && content.get("parts").isJsonArray()) {
                                                    JsonArray parts = content.getAsJsonArray("parts");
                                                    
                                                    for (int i = 0; i < parts.size(); i++) {
                                                        JsonObject part = parts.get(i).getAsJsonObject();
                                                        
                                                        if (part.has("text") && !part.get("text").isJsonNull()) {
                                                            String textPart = part.get("text").getAsString();
                                                            textBuilder.append(textPart);
                                                            handler.onTextUpdate(textPart);
                                                        }
                                                        
                                                        if (part.has("functionCall")) {
                                                            JsonObject functionCall = part.getAsJsonObject("functionCall");
                                                            String tcId = "call_" + java.util.UUID.randomUUID().toString().substring(0, 8);
                                                            String funcName = functionCall.has("name") ? functionCall.get("name").getAsString() : "";
                                                            String funcArgs = functionCall.has("args") ? gson.toJson(functionCall.get("args")) : "{}";
                                                            toolCalls.add(new ToolCall(tcId, funcName, funcArgs));
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (JsonSyntaxException e) {
                                    handler.onError(new ResponseException(name, operation, ResponseException.ResponseErrorType.MALFORMED_JSON, e));
                                    return;
                                }
                            }
                        }

                        if (isCancelled) {
                            handler.onError(new StreamCancelledException(name, operation, StreamCancelledException.CancellationReason.USER_REQUESTED));
                        } else if (!handler.shouldContinue()) {
                            handler.onError(new StreamCancelledException(name, operation, StreamCancelledException.CancellationReason.USER_REQUESTED));
                        } else {
                            handler.onStreamComplete(finishReason, textBuilder.toString(), toolCalls);
                        }
                    } catch (IOException e) {
                        handler.onError(new ResponseException(name, operation, ResponseException.ResponseErrorType.STREAM_INTERRUPTED, e));
                    }
                }
            }
        });
    }
    @Override
    protected String extractApiErrorCode(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return null;
        }

        try {
            JsonElement root = com.google.gson.JsonParser.parseString(responseBody);
            if (root.isJsonObject() && root.getAsJsonObject().has("error")) {
                JsonElement errorElement = root.getAsJsonObject().get("error");
                if (errorElement.isJsonObject()) {
                    JsonObject errorObj = errorElement.getAsJsonObject();
                    if (errorObj.has("status") && !errorObj.get("status").isJsonNull()) {
                        return errorObj.get("status").getAsString();
                    } else if (errorObj.has("code") && !errorObj.get("code").isJsonNull()) {
                        return errorObj.get("code").getAsString();
                    }
                }
            }
        } catch (JsonSyntaxException e) {
        }
        return super.extractApiErrorCode(responseBody);
    }
    
    @Override
    public void getEmbeddingsAsync(String text, EmbeddingCallback callback) {
        callback.onError(new APIProviderException(
            APIProviderException.ErrorCategory.CONFIGURATION,
            name, "get_embeddings",
            "Google GenAI provider does not support embeddings in this implementation"));
    }
}
