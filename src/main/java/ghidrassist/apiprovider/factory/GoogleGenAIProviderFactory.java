package ghidrassist.apiprovider.factory;

import ghidrassist.apiprovider.APIProvider;
import ghidrassist.apiprovider.APIProviderConfig;
import ghidrassist.apiprovider.GoogleGenAIProvider;

public class GoogleGenAIProviderFactory implements APIProviderFactory {

    @Override
    public APIProvider createProvider(APIProviderConfig config) throws UnsupportedProviderException {
        if (!supports(config.getType())) {
            throw new UnsupportedProviderException(config.getType(), getFactoryName());
        }

        return new GoogleGenAIProvider(
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
    public boolean supports(APIProvider.ProviderType type) {
        return type == APIProvider.ProviderType.GOOGLE_GENAI_API;
    }

    @Override
    public APIProvider.ProviderType getProviderType() {
        return APIProvider.ProviderType.GOOGLE_GENAI_API;
    }

    @Override
    public String getFactoryName() {
        return "GoogleGenAIProviderFactory";
    }
}
