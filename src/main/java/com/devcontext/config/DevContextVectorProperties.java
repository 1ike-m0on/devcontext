package com.devcontext.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devcontext.vector")
public record DevContextVectorProperties(
        String provider,
        Qdrant qdrant
) {

    public DevContextVectorProperties {
        if (provider == null || provider.isBlank()) {
            provider = "jdbc";
        }
        if (qdrant == null) {
            qdrant = new Qdrant("http://localhost:6333", null, "Cosine", Duration.ofSeconds(10));
        }
    }

    public record Qdrant(
            String baseUrl,
            String apiKey,
            String distance,
            Duration timeout
    ) {

        public Qdrant {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "http://localhost:6333";
            }
            if (distance == null || distance.isBlank()) {
                distance = "Cosine";
            }
            if (timeout == null) {
                timeout = Duration.ofSeconds(10);
            }
        }
    }
}
