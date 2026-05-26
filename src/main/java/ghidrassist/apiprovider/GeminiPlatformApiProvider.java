package ghidrassist.apiprovider;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import ghidrassist.apiprovider.exceptions.APIProviderException;
import ghidrassist.apiprovider.exceptions.NetworkException;
import ghidrassist.apiprovider.exceptions.ResponseException;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Google Gemini Provider - OpenAI-compatible API at
 * https://generativelanguage.googleapis.com/v1beta/openai/.
 * Inherits all chat, streaming, function calling, and model listing from OpenAIPlatformApiProvider.
 * Gemini's OpenAI-compatible endpoint does not support embeddings.
 *
 * The parent's sanitizeToolCallsInPayload aggressively reorders messages
 * and strips tool_calls that it considers "orphaned". Gemini's endpoint
 * handles its own message validation and rejects the parent's rearranged
 * payloads. This override builds messages cleanly without sanitization.
 */
public class GeminiPlatformApiProvider extends OpenAIPlatformApiProvider {

    public GeminiPlatformApiProvider(String name, String model, Integer maxTokens, String url,
                                      String key, boolean disableTlsVerification, boolean bypassProxy, Integer timeout) {
        super(name, model, maxTokens, url, key, disableTlsVerification, bypassProxy, timeout);

        // Override the type to GEMINI_PLATFORM_API
        this.type = ProviderType.GEMINI_PLATFORM_API;
    }

    public static GeminiPlatformApiProvider fromConfig(APIProviderConfig config) {
        return new GeminiPlatformApiProvider(
            config.getName(),
            config.getModel(),
            config.getMaxTokens(),
            config.getUrl(),
            config.getKey(),
            config.isDisableTlsVerification(),
            config.isBypassProxy(),
            config.getTimeout()
        );
    }

    @Override
    public void getEmbeddingsAsync(String text, EmbeddingCallback callback) {
        callback.onError(new APIProviderException(
            APIProviderException.ErrorCategory.CONFIGURATION,
            name, "get_embeddings",
            "Gemini OpenAI-compatible endpoint does not support embeddings"));
    }

    /**
     * Gemini's OpenAI-compatible endpoint does NOT support tool_choice: "required".
     * It only accepts "auto", "none", or {"type":"function","function":{"name":...}}.
     * Always use "auto" to avoid "Request contains an invalid argument" errors.
     */
    private String geminiToolChoice() {
        return "auto";
    }

    /**
     * Build the chat payload WITHOUT the parent's sanitizeToolCallsInPayload step.
     */
    @Override
    protected JsonObject buildChatCompletionPayload(List<ChatMessage> messages, boolean stream) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", super.getModel());

        // Handle different token field names based on model.
        // Reasoning models (o1, o3, o4, gpt-5 families) require
        // max_completion_tokens; max_tokens is rejected with 400.
        String modelName = super.getModel();
        boolean isReasoningModel = modelName != null &&
            (modelName.startsWith("o1") || modelName.startsWith("o3") ||
             modelName.startsWith("o4") || modelName.startsWith("gpt-5"));
        Integer tokenLimit = super.getMaxTokens();
        if (isReasoningModel) {
            payload.addProperty("max_completion_tokens", tokenLimit);
        } else {
            payload.addProperty("max_tokens", tokenLimit != null ? tokenLimit : 16384);
        }

        payload.addProperty("stream", stream);

        ReasoningConfig reasoning = getReasoningConfig();
        if (reasoning != null && reasoning.isEnabled()) {
            payload.addProperty("reasoning_effort", reasoning.getEffortString());
        }

        JsonArray messagesArray = new JsonArray();
        for (ChatMessage message : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", message.getRole());

            if (message.getContent() != null) {
                msgObj.addProperty("content", message.getContent());
            } else if ("tool".equals(message.getRole())) {
                msgObj.addProperty("content", "");
            }

            if (message.getToolCalls() != null) {
                msgObj.add("tool_calls", message.getToolCalls());
            }

            if (message.getToolCallId() != null) {
                msgObj.addProperty("tool_call_id", message.getToolCallId());
            }

            messagesArray.add(msgObj);
        }

        // Skip sanitizeToolCallsInPayload — Gemini validates messages itself
        payload.add("messages", messagesArray);
        return payload;
    }

    @Override
    public void streamChatCompletionWithFunctions(
        List<ChatMessage> messages,
        List<Map<String, Object>> functions,
        ToolChoiceMode toolChoiceMode,
        OpenAIPlatformApiProvider.StreamingFunctionHandler handler
    ) throws APIProviderException {
        JsonObject payload = buildChatCompletionPayload(messages, true);
        payload.add("tools", gson.toJsonTree(functions));
        payload.addProperty("tool_choice", geminiToolChoice());
        executeStreamingFunctionsWithRetry(payload, handler, "stream_chat_completion_with_functions", 0);
    }

    @Override
    public String createChatCompletionWithFunctions(
        List<ChatMessage> messages,
        List<Map<String, Object>> functions,
        ToolChoiceMode toolChoiceMode
    ) throws APIProviderException {
        JsonObject payload = buildChatCompletionPayload(messages, false);
        payload.add("tools", gson.toJsonTree(functions));
        payload.addProperty("tool_choice", geminiToolChoice());

        Request request = buildPostRequestForEndpoint(OPENAI_CHAT_ENDPOINT, payload);
        try (Response response = executeWithRetry(request, "createChatCompletionWithFunctions")) {
            String responseBody = response.body().string();
            StringReader responseStr = new StringReader(responseBody.replaceFirst("```json", "{").replaceFirst("```$", "}"));

            JsonReader jsonReader = new JsonReader(responseStr);
            jsonReader.setLenient(true);
            JsonElement responseElem = JsonParser.parseReader(jsonReader);
            if (responseElem == null || !responseElem.isJsonObject()) {
                throw new ResponseException(name, "createChatCompletionWithFunctions",
                    ResponseException.ResponseErrorType.EMPTY_RESPONSE);
            }
            JsonObject responseObj = responseElem.getAsJsonObject();
            JsonObject message = new JsonObject();
            if (responseObj.has("message")) {
                message = responseObj.getAsJsonObject("message");
            }

            StringBuilder resultBuilder = new StringBuilder();
            resultBuilder.append("{\"message\":").append(message.toString()).append(",\"choices\":[{\"finish_reason\":\"");

            String finishReason = "stop";
            if (responseObj.has("choices")) {
                JsonArray choices = responseObj.getAsJsonArray("choices");
                if (choices.size() > 0 && choices.get(0).isJsonObject()) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    if (choice.has("finish_reason")) {
                        finishReason = choice.get("finish_reason").getAsString();
                    }
                }
            }
            resultBuilder.append(finishReason).append("\"}]}");

            if (responseObj.has("usage")) {
                resultBuilder.append(",\"usage\":").append(responseObj.get("usage").toString());
            }
            resultBuilder.append("}");

            return resultBuilder.toString();
        } catch (IOException e) {
            throw new NetworkException(name, "createChatCompletionWithFunctions",
                NetworkException.NetworkErrorType.CONNECTION_FAILED);
        }
    }

    @Override
    public String createChatCompletionWithFunctionsFullResponse(
        List<ChatMessage> messages,
        List<Map<String, Object>> functions,
        ToolChoiceMode toolChoiceMode
    ) throws APIProviderException {
        JsonObject payload = buildChatCompletionPayload(messages, false);
        payload.add("tools", gson.toJsonTree(functions));
        payload.addProperty("tool_choice", geminiToolChoice());

        Request request = buildPostRequestForEndpoint(OPENAI_CHAT_ENDPOINT, payload);
        try (Response response = executeWithRetry(request, "createChatCompletionWithFunctionsFullResponse")) {
            return response.body().string();
        } catch (IOException e) {
            throw new NetworkException(name, "createChatCompletionWithFunctionsFullResponse",
                NetworkException.NetworkErrorType.CONNECTION_FAILED);
        }
    }
}
