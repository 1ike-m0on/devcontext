package com.devcontext.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devcontext.llm")
public record DevContextLlmProperties(
        String provider,
        Mock mock,
        Gemini gemini,
        DeepSeek deepseek
) {

    public DevContextLlmProperties {
        provider = normalizeProvider(provider);
        if (mock == null) {
            mock = new Mock("mock-llm");
        }
        if (gemini == null) {
            gemini = new Gemini(null, "gemini-2.0-flash", "https://generativelanguage.googleapis.com/v1beta", Duration.ofSeconds(60));
        }
        if (deepseek == null) {
            deepseek = new DeepSeek(null, "deepseek-chat", "https://api.deepseek.com", Duration.ofSeconds(120));
        }
    }

    public String modelName() {
        if ("gemini".equals(provider)) {
            return gemini.model();
        }
        if ("deepseek".equals(provider)) {
            return deepseek.model();
        }
        return mock.model();
    }

    public String providerModelLabel() {
        return provider + "/" + modelName();
    }

    public boolean supportedProvider() {
        return supportedProviders().contains(provider);
    }

    public List<String> supportedProviders() {
        return List.of("mock", "gemini", "deepseek");
    }

    public String maskSecrets(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String masked = value;
        masked = maskOne(masked, gemini.apiKey());
        masked = maskOne(masked, deepseek.apiKey());
        return masked;
    }

    private String maskOne(String value, String secret) {
        if (secret == null || secret.isBlank()) {
            return value;
        }
        return value.replace(secret, "[masked]");
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "mock";
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    public record Mock(
            String model
    ) {

        public Mock {
            if (model == null || model.isBlank()) {
                model = "mock-llm";
            }
        }
    }

    public record Gemini(
            String apiKey,
            String model,
            String baseUrl,
            Duration timeout
    ) {

        public Gemini {
            if (model == null || model.isBlank()) {
                model = "gemini-2.0-flash";
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://generativelanguage.googleapis.com/v1beta";
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                timeout = Duration.ofSeconds(60);
            }
        }
    }

    public record DeepSeek(
            String apiKey,
            String model,
            String baseUrl,
            Duration timeout
    ) {

        public DeepSeek {
            if (model == null || model.isBlank()) {
                model = "deepseek-chat";
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://api.deepseek.com";
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                timeout = Duration.ofSeconds(120);
            }
        }
    }
}
