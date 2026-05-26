package ghidrassist.apiprovider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ghidrassist.apiprovider.exceptions.APIProviderException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIPlatformApiProviderTest {

    private final OpenAIPlatformApiProvider provider = new OpenAIPlatformApiProvider(
            "testName", "testModel", 1024, "http://test-url", "testKey",
            false, false, 3000
    );

    @Test
    void testExtractModelIdsFromResponse_withDataKey() throws Exception {
        String validResponse = "{" +
                "\"data\": [{\"id\": \"model1\"}, {\"id\": \"model2\"}]" +
                "}";

        List<String> models = provider.extractModelIdsFromResponse(validResponse);

        assertNotNull(models);
        assertEquals(2, models.size());
        assertEquals("model1", models.get(0));
        assertEquals("model2", models.get(1));
    }

    @Test
    void testExtractModelIdsFromResponse_withModelsKey() throws Exception {
        String fallbackResponse = "{" +
                "\"models\": [{\"id\": \"modelA\"}, {\"id\": \"modelB\"}]" +
                "}";

        List<String> models = provider.extractModelIdsFromResponse(fallbackResponse);

        assertNotNull(models);
        assertEquals(2, models.size());
        assertEquals("modelA", models.get(0));
        assertEquals("modelB", models.get(1));
    }

    @Test
    void testExtractModelIdsFromResponse_withInvalidJson() {
        String invalidJson = "{"; // Malformed JSON

        assertThrows(APIProviderException.class, () -> {
            provider.extractModelIdsFromResponse(invalidJson);
        });
    }

    @Test
    void testExtractModelIdsFromResponse_withEmptyResponse() throws APIProviderException {
        String emptyResponse = "{}";

        List<String> models = provider.extractModelIdsFromResponse(emptyResponse);

        assertNotNull(models);
        assertTrue(models.isEmpty());
    }

    @Test
    void testExtractModelIdsFromResponse_withNoRelevantKeys() throws APIProviderException {
        String noKeysResponse = "{" +
                "\"otherKey\": [{\"id\": \"modelX\"}]" +
                "}";

        List<String> models = provider.extractModelIdsFromResponse(noKeysResponse);

        assertNotNull(models);
        assertTrue(models.isEmpty());
    }
}
