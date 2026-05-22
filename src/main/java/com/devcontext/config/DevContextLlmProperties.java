package com.devcontext.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devcontext.llm")
public record DevContextLlmProperties(
        String provider,
        Gemini gemini
) {

    public DevContextLlmProperties {
        if (provider == null || provider.isBlank()) {
            provider = "mock";
        }
        if (gemini == null) {
            gemini = new Gemini(null, "gemini-2.0-flash", "https://generativelanguage.googleapis.com/v1beta");
        }
    }

    public String modelName() {
        if ("gemini".equalsIgnoreCase(provider)) {
            return gemini.model();
        }
        return "mock-llm";
    }

    public record Gemini(
            String apiKey,
            String model,
            String baseUrl
    ) {

        public Gemini {
            if (model == null || model.isBlank()) {
                model = "gemini-2.0-flash";
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://generativelanguage.googleapis.com/v1beta";
            }
        }
    }
}
