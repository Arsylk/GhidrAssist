package ghidrassist.apiprovider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ghidrassist.apiprovider.exceptions.APIProviderException;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GoogleGenAIProviderTest {

    private final GoogleGenAIProvider provider = new GoogleGenAIProvider(
            "testGoogle", "gemini-1.5-pro", 1024, "https://test-google.com/v1", "testKey",
            false, false, 3000
    );

    @Test
    void testExtractModelIdsFromResponse_googleFormat() throws APIProviderException {
        String json = "{\"models\": [{\"name\": \"models/gemini-pro\"}, {\"name\": \"models/gemini-flash\"}]}";
        List<String> ids = provider.extractModelIdsFromResponse(json);
        assertEquals(2, ids.size());
        assertEquals("models/gemini-pro", ids.get(0));
    }

    @Test
    void testExtractModelIdsFromResponse_openAIFormat() throws APIProviderException {
        String json = "{\"data\": [{\"id\": \"gpt-4\"}, {\"id\": \"gpt-3.5-turbo\"}]}";
        List<String> ids = provider.extractModelIdsFromResponse(json);
        assertEquals(2, ids.size());
        assertEquals("gpt-4", ids.get(0));
    }

    @Test
    void testEnsureModelPathPrefix() {
        assertEquals("models/my-model", GoogleGenAIProvider.ensureModelPathPrefix("my-model"));
        assertEquals("models/my-model", GoogleGenAIProvider.ensureModelPathPrefix("models/my-model"));
        assertEquals("models/my-model", GoogleGenAIProvider.ensureModelPathPrefix("/models/my-model"));
        assertEquals("models/my-model", GoogleGenAIProvider.ensureModelPathPrefix("//models/my-model"));
    }

    @Test
    void testStripUnsupportedSchemaKeys_removesGeminiUnsupportedMetadata() throws Exception {
        Method method = GoogleGenAIProvider.class.getDeclaredMethod("stripUnsupportedSchemaKeys", JsonElement.class);
        method.setAccessible(true);

        JsonElement sanitized = (JsonElement) method.invoke(provider, JsonParser.parseString("{" +
                "\"type\":\"object\"," +
                "\"title\":\"Root\"," +
                "\"default\":{}," +
                "\"properties\":{" +
                "  \"field\": {" +
                "    \"type\":\"string\"," +
                "    \"title\":\"Field Title\"," +
                "    \"description\":\"desc\"," +
                "    \"default\":\"abc\"" +
                "  }" +
                "}," +
                "\"additionalProperties\":false" +
                "}"));

        JsonObject root = sanitized.getAsJsonObject();
        JsonObject field = root.getAsJsonObject("properties").getAsJsonObject("field");

        assertFalse(root.has("title"));
        assertFalse(root.has("default"));
        assertFalse(root.has("additionalProperties"));
        assertTrue(field.has("description"));
        assertFalse(field.has("title"));
        assertFalse(field.has("default"));
    }
}
