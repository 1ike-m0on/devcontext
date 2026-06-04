package com.devcontext.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devcontext.llm")
public record DevContextLlmProperties(
        String provider,
        Gemini gemini,
        DeepSeek deepseek
) {

    public DevContextLlmProperties {
        if (provider == null || provider.isBlank()) {
            provider = "mock";
        }
        if (gemini == null) {
            gemini = new Gemini(null, "gemini-2.0-flash", "https://generativelanguage.googleapis.com/v1beta", Duration.ofSeconds(60));
        }
        if (deepseek == null) {
            deepseek = new DeepSeek(null, "deepseek-chat", "https://api.deepseek.com", Duration.ofSeconds(120));
        }
    }

    public String modelName() {
        if ("gemini".equalsIgnoreCase(provider)) {
            return gemini.model();
        }
        if ("deepseek".equalsIgnoreCase(provider)) {
            return deepseek.model();
        }
        return "mock-llm";
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
