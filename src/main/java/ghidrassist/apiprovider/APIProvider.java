package ghidrassist.apiprovider;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import ghidrassist.LlmApi;
import ghidrassist.apiprovider.capabilities.ChatProvider;
import ghidrassist.apiprovider.exceptions.*;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public abstract class APIProvider implements ChatProvider {
    public enum ProviderType {
        ANTHROPIC_CLAUDE_CLI,
        ANTHROPIC_PLATFORM_API,
        AZURE_OPENAI,
        GEMINI_OAUTH,
        GEMINI_PLATFORM_API,
GOOGLE_GENAI_API,
        LITELLM,
        LMSTUDIO,
        OLLAMA,
        OPENAI_OAUTH,
        OPENAI_PLATFORM_API,
        OPENWEBUI,
        XAI_PLATFORM_API
    }

    protected String name;
    protected String model;
    protected Integer maxTokens;
    protected String url;
    protected String key;
    protected boolean disableTlsVerification;
    protected boolean bypassProxy;
    protected ProviderType type;
    protected OkHttpClient client;
    protected Duration timeout;
    protected RetryHandler retryHandler;
    protected ReasoningConfig reasoningConfig;
    protected Boolean useAdaptiveThinking;

    public APIProvider(String name, ProviderType type, String model, Integer maxTokens,
                      String url, String key, boolean disableTlsVerification, boolean bypassProxy, Integer timeout2) {
        this.name = name;
        this.type = type;
        this.model = model;
        this.maxTokens = maxTokens;
        String normalizedUrl = url != null ? url : "";
        this.url = normalizedUrl.endsWith("/") ? normalizedUrl : normalizedUrl + "/";
        this.key = key;
        this.disableTlsVerification = disableTlsVerification;
        this.bypassProxy = bypassProxy;
        this.timeout = Duration.ofSeconds(timeout2 != null ? timeout2 : 90);
        this.retryHandler = new RetryHandler(50, this);
        this.reasoningConfig = new ReasoningConfig(); // Default to NONE
        this.client = buildClient();
    }

    // Getters
    public String getName() { return name; }
    public ProviderType getType() { return type; }
    public String getModel() { return model; }
    public Integer getMaxTokens() { return maxTokens; }
    public String getUrl() { return url; }
    public String getKey() { return key; }
    public boolean isDisableTlsVerification() { return disableTlsVerification; }
    public boolean isBypassProxy() { return bypassProxy; }

    public Boolean getUseAdaptiveThinking() { return useAdaptiveThinking; }
    public void setUseAdaptiveThinking(Boolean useAdaptiveThinking) { this.useAdaptiveThinking = useAdaptiveThinking; }

    // Reasoning configuration
    public ReasoningConfig getReasoningConfig() {
        ReasoningConfig result = reasoningConfig != null ? reasoningConfig : new ReasoningConfig();
        ghidra.util.Msg.info(this, "DEBUG [APIProvider.getReasoningConfig]: Returning " +
            result.getEffort() + ", enabled=" + result.isEnabled() +
            " (field is " + (reasoningConfig != null ? "NOT NULL" : "NULL") + ")");
        return result;
    }

    public void setReasoningConfig(ReasoningConfig config) {
        this.reasoningConfig = config != null ? config : new ReasoningConfig();
        ghidra.util.Msg.info(this, "DEBUG [APIProvider.setReasoningConfig]: Set to " +
            this.reasoningConfig.getEffort() + ", enabled=" + this.reasoningConfig.isEnabled() +
            " (input was " + (config != null ? config.getEffort().toString() : "NULL") + ")");
    }

    protected abstract OkHttpClient buildClient();
    public abstract String createChatCompletion(List<ChatMessage> messages) throws APIProviderException;
    public abstract void streamChatCompletion(List<ChatMessage> messages, LlmApi.LlmResponseHandler handler) throws APIProviderException;
    public abstract String createChatCompletionWithFunctions(List<ChatMessage> messages, List<Map<String, Object>> functions) throws APIProviderException;
    public abstract String createChatCompletionWithFunctionsFullResponse(List<ChatMessage> messages, List<Map<String, Object>> functions) throws APIProviderException;
    public abstract List<String> getAvailableModels() throws APIProviderException;
    public abstract void getEmbeddingsAsync(String text, EmbeddingCallback callback);
	public void setTimeout(Integer timeout2) { this.timeout = Duration.ofSeconds(timeout2 != null ? timeout2 : 90); }
	public Integer getTimeout() { return Math.toIntExact(this.timeout.getSeconds()); }

    /**
     * Allows providers to prepare shared state before concurrent requests fan out.
     */
    public void prepareForConcurrentRequests() throws APIProviderException {
        // Default no-op.
    }

    public String createChatCompletionWithFunctions(List<ChatMessage> messages,
                                                    List<Map<String, Object>> functions,
                                                    ToolChoiceMode toolChoiceMode) throws APIProviderException {
        return createChatCompletionWithFunctions(messages, functions);
    }

    public String createChatCompletionWithFunctionsFullResponse(List<ChatMessage> messages,
                                                                List<Map<String, Object>> functions,
                                                                ToolChoiceMode toolChoiceMode) throws APIProviderException {
        return createChatCompletionWithFunctionsFullResponse(messages, functions);
    }

    public void testConnection() throws APIProviderException {
        List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(new ChatMessage(ChatMessage.ChatMessageRole.USER,
            "Test message. Please respond with 'OK'."));
        createChatCompletion(messages);
    }

    protected OkHttpClient.Builder configureClientBuilder(OkHttpClient.Builder builder) {
        if (bypassProxy) {
            builder.proxy(Proxy.NO_PROXY);
        }
        return builder;
    }

    
    public double[] getEmbeddings(String text) throws APIProviderException {
        CompletableFuture<double[]> future = new CompletableFuture<>();
        
        getEmbeddingsAsync(text, new EmbeddingCallback() {
            @Override
            public void onSuccess(double[] embedding) {
                future.complete(embedding);
            }
            
            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });
        
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StreamCancelledException(name, "get_embeddings", 
                StreamCancelledException.CancellationReason.USER_REQUESTED, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof APIProviderException) {
                throw (APIProviderException) cause;
            }
            throw new APIProviderException(APIProviderException.ErrorCategory.SERVICE_ERROR, 
                name, "get_embeddings", "Failed to get embeddings: " + e.getMessage());
        } catch (TimeoutException e) {
            throw new APIProviderException(APIProviderException.ErrorCategory.TIMEOUT, 
                name, "get_embeddings", "Embedding request timed out");
        }
    }

    public interface EmbeddingCallback {
        void onSuccess(double[] embedding);
        void onError(Throwable error);
    }
    
    /**
     * Handle network-related exceptions and convert to appropriate APIProviderException
     */
    protected APIProviderException handleNetworkError(Exception e, String operation) {
        String message = e.getMessage();
        String lowerMessage = message != null ? message.toLowerCase() : "";

        if (e instanceof IOException && lowerMessage.contains("token refresh failed")) {
            String apiErrorCode = classifyOAuthRefreshFailure(lowerMessage);
            String authMessage = message != null ? message : "OAuth token refresh failed";
            if (requiresOAuthReauthentication(apiErrorCode) &&
                !lowerMessage.contains("re-authentication required") &&
                !lowerMessage.contains("re-authenticate") &&
                !lowerMessage.contains("reauthenticate")) {
                authMessage += " Re-authentication required.";
            }
            return new AuthenticationException(
                name,
                operation,
                lowerMessage.contains("401") ? 401 : -1,
                apiErrorCode,
                authMessage
            );
        }

        if (e instanceof SocketTimeoutException) {
            return new NetworkException(name, operation, NetworkException.NetworkErrorType.TIMEOUT, e);
        } else if (e instanceof SSLException) {
            return new NetworkException(name, operation, NetworkException.NetworkErrorType.SSL_ERROR, e);
        } else if (e instanceof ConnectException) {
            return new NetworkException(name, operation, NetworkException.NetworkErrorType.CONNECTION_FAILED, e);
        } else if (e instanceof UnknownHostException) {
            return new NetworkException(name, operation, NetworkException.NetworkErrorType.DNS_ERROR, e);
        } else if (e instanceof IOException && lowerMessage.contains("connection")) {
            return new NetworkException(name, operation, NetworkException.NetworkErrorType.CONNECTION_LOST, e);
        }
        
        // Default network error
        return new NetworkException(name, operation, "Network error: " + message);
    }

    private String classifyOAuthRefreshFailure(String lowerMessage) {
        if (lowerMessage.contains("refresh_token_reused") ||
            lowerMessage.contains("refresh token has already been used")) {
            return "refresh_token_reused";
        }
        if (lowerMessage.contains("refresh_token_expired") ||
            lowerMessage.contains("refresh token has expired") ||
            lowerMessage.contains("expired refresh token")) {
            return "refresh_token_expired";
        }
        if (lowerMessage.contains("refresh_token_revoked") ||
            lowerMessage.contains("refresh token has been revoked") ||
            lowerMessage.contains("refresh token revoked")) {
            return "refresh_token_revoked";
        }
        if (lowerMessage.contains("refresh_token_invalid") ||
            lowerMessage.contains("invalid refresh token") ||
            lowerMessage.contains("refresh token is invalid") ||
            lowerMessage.contains("invalid_grant")) {
            return "refresh_token_invalid";
        }
        return null;
    }

    private boolean requiresOAuthReauthentication(String apiErrorCode) {
        return "refresh_token_reused".equals(apiErrorCode) ||
            "refresh_token_expired".equals(apiErrorCode) ||
            "refresh_token_revoked".equals(apiErrorCode) ||
            "refresh_token_invalid".equals(apiErrorCode);
    }
    
    /**
     * Handle HTTP response errors and convert to appropriate APIProviderException
     */
    protected APIProviderException handleHttpError(Response response, String operation) {
        String responseBody = null;
        
        try {
            if (response.body() != null) {
                responseBody = response.body().string();
            }
        } catch (IOException e) {
            // Ignore errors reading response body
        }
        
        return handleHttpError(response, responseBody, operation);
    }
    
    /**
     * Handle HTTP response errors with preread response body
     */
    protected APIProviderException handleHttpError(Response response, String responseBody, String operation) {
        int statusCode = response.code();
        String apiErrorCode = extractApiErrorCode(responseBody);
        String errorMessage = extractErrorMessage(responseBody, statusCode);
        
        switch (statusCode) {
            case 401:
                return new AuthenticationException(name, operation, statusCode, apiErrorCode, 
                    errorMessage != null ? errorMessage : "Invalid or missing API key");
                    
            case 403:
                return new AuthenticationException(name, operation, statusCode, apiErrorCode, 
                    errorMessage != null ? errorMessage : "API key does not have sufficient permissions");
                    
            case 429:
                Integer retryAfter = extractRetryAfter(response, responseBody);
                return new RateLimitException(name, operation, statusCode, apiErrorCode, 
                    errorMessage != null ? errorMessage : "Rate limit exceeded", retryAfter);
                    
            case 400:
                // Be more specific about model errors - look for definitive phrases, not just "model"
                String lowerErrorMessage = errorMessage != null ? errorMessage.toLowerCase() : "";
                if (lowerErrorMessage.contains("model not found") ||
                    lowerErrorMessage.contains("model_not_found") ||
                    lowerErrorMessage.contains("unknown model") ||
                    lowerErrorMessage.contains("invalid model")) {
                    // Preserve the actual error message instead of using a generic one
                    return new ModelException(name, operation, ModelException.ModelErrorType.MODEL_NOT_FOUND,
                        statusCode, apiErrorCode, errorMessage);
                } else if (lowerErrorMessage.contains("context length") ||
                           lowerErrorMessage.contains("context_length") ||
                           lowerErrorMessage.contains("maximum context")) {
                    return new ModelException(name, operation, ModelException.ModelErrorType.CONTEXT_LENGTH_EXCEEDED,
                        statusCode, apiErrorCode, errorMessage);
                } else if (lowerErrorMessage.contains("token limit") ||
                           lowerErrorMessage.contains("max_tokens") ||
                           lowerErrorMessage.contains("maximum.*token")) {
                    return new ModelException(name, operation, ModelException.ModelErrorType.TOKEN_LIMIT_EXCEEDED,
                        statusCode, apiErrorCode, errorMessage);
                }
                return new APIProviderException(APIProviderException.ErrorCategory.CONFIGURATION,
                    name, operation, statusCode, apiErrorCode,
                    errorMessage != null ? errorMessage : "Bad request");
                    
            case 404:
                if (operation.contains("model")) {
                    return new ModelException(name, operation, ModelException.ModelErrorType.MODEL_NOT_FOUND, 
                        statusCode, apiErrorCode);
                }
                return new APIProviderException(APIProviderException.ErrorCategory.CONFIGURATION, 
                    name, operation, statusCode, apiErrorCode, 
                    errorMessage != null ? errorMessage : "Resource not found");
                    
            case 500:
            case 502:
            case 503:
            case 504:
                return new APIProviderException(APIProviderException.ErrorCategory.SERVICE_ERROR, 
                    name, operation, statusCode, apiErrorCode, 
                    errorMessage != null ? errorMessage : "Service error", true, null, null);
                    
            default:
                return new APIProviderException(APIProviderException.ErrorCategory.SERVICE_ERROR, 
                    name, operation, statusCode, apiErrorCode, 
                    errorMessage != null ? errorMessage : "HTTP error " + statusCode);
        }
    }
    
    /**
     * Extract API error code from response body (provider-specific)
     */
    protected String extractApiErrorCode(String responseBody) {
        // Default implementation - subclasses should override for provider-specific logic
        return null;
    }
    
    /**
     * Extract error message from response body (provider-specific)
     *
     * Default implementation uses Gson to robustly walk common error shapes:
     *   { "error": { "message": "..." } }              (OpenAI / Azure)
     *   { "error": "..." }                              (simple string)
     *   { "message": "..." }                            (Anthropic / generic)
     *   { "error": { "error": { "message": "..." } } }  (nested variants)
     *   { "detail": "..." }                             (LiteLLM / FastAPI)
     *
     * The legacy substring-based extractor in prior versions broke on escaped
     * quotes and could surface a single backslash ("\") as the error message,
     * rendering in the UI as "Message: \". Using Gson fixes that completely.
     */
    protected String extractErrorMessage(String responseBody, int statusCode) {
        if (responseBody == null || responseBody.isEmpty()) {
            return null;
        }

        try {
            JsonElement root = JsonParser.parseString(responseBody);
            String message = findFirstMessageField(root, 0);
            if (message != null && !message.isBlank()) {
                return message.trim();
            }
        } catch (JsonSyntaxException e) {
            // Not valid JSON - fall through to raw body fallback
        }

        // Fallback: truncated raw body so the UI at least shows something real
        return responseBody.length() > 200 ? responseBody.substring(0, 200) + "..." : responseBody;
    }

    /**
     * Recursively search a JSON tree for the first string field commonly used
     * for error messages. Depth-bounded to avoid pathological inputs.
     */
    private String findFirstMessageField(JsonElement element, int depth) {
        if (element == null || depth > 6) {
            return null;
        }

        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            // Preferred keys in priority order
            String[] keys = new String[] { "message", "detail", "error_description", "error_message" };
            for (String key : keys) {
                if (obj.has(key)) {
                    JsonElement v = obj.get(key);
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                        String s = v.getAsString();
                        if (s != null && !s.isBlank()) {
                            return s;
                        }
                    }
                }
            }
            // "error" can be either a string or a nested object
            if (obj.has("error")) {
                JsonElement err = obj.get("error");
                if (err.isJsonPrimitive() && err.getAsJsonPrimitive().isString()) {
                    String s = err.getAsString();
                    if (s != null && !s.isBlank()) {
                        return s;
                    }
                }
                String nested = findFirstMessageField(err, depth + 1);
                if (nested != null) return nested;
            }
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                String nested = findFirstMessageField(entry.getValue(), depth + 1);
                if (nested != null) return nested;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                String nested = findFirstMessageField(item, depth + 1);
                if (nested != null) return nested;
            }
        }
        return null;
    }
    
    /**
     * Extract retry-after value from response
     */
    protected Integer extractRetryAfter(Response response, String responseBody) {
        // Check Retry-After header
        String retryAfterHeader = response.header("Retry-After");
        if (retryAfterHeader != null) {
            try {
                return Integer.parseInt(retryAfterHeader);
            } catch (NumberFormatException e) {
                // Ignore parsing errors
            }
        }
        
        // Check for retry-after in response body (provider-specific)
        if (responseBody != null && responseBody.contains("retry")) {
            // Basic parsing - subclasses should override for provider-specific logic
            try {
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"retry.*?(\\d+)");
                java.util.regex.Matcher matcher = pattern.matcher(responseBody);
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
            } catch (Exception e) {
                // Ignore parsing errors
            }
        }
        
        return null; // No retry-after information found
    }
    
    /**
     * Execute an HTTP request with retry logic for handling rate limits and transient errors
     */
    protected Response executeWithRetry(okhttp3.Request request, String operationName) throws APIProviderException {
        return retryHandler.executeWithRetryCallable(() -> {
            Response response = client.newCall(request).execute();
            if (!response.isSuccessful()) {
                throw handleHttpError(response, operationName);
            }
            return response;
        }, operationName);
    }

    // ========== Token Counting Methods (for Context Management) ==========

    /**
     * Count tokens in a list of chat messages.
     * Default implementation returns -1 (unsupported).
     * Providers with native token counting APIs should override this method.
     *
     * @param messages List of chat messages
     * @return Token count, or -1 if provider doesn't support token counting
     * @throws APIProviderException if token counting fails
     */
    public int countTokens(List<ChatMessage> messages) throws APIProviderException {
        return -1; // Not supported by default
    }

    /**
     * Count tokens in a text string.
     * Default implementation returns -1 (unsupported).
     * Providers with native token counting APIs should override this method.
     *
     * @param text Text to count tokens for
     * @return Token count, or -1 if provider doesn't support token counting
     * @throws APIProviderException if token counting fails
     */
    public int countTokens(String text) throws APIProviderException {
        return -1; // Not supported by default
    }

    /**
     * Estimate tokens required for tool definitions.
     * Default implementation returns -1 (unsupported).
     * Providers can override with more accurate estimates.
     *
     * @param tools List of tool definitions in OpenAI function format
     * @return Token estimate, or -1 if provider doesn't support estimation
     * @throws APIProviderException if estimation fails
     */
    public int estimateTokensForTools(List<Map<String, Object>> tools) throws APIProviderException {
        return -1; // Not supported by default
    }

}
