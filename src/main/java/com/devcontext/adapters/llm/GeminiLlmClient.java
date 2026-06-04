package com.devcontext.adapters.llm;

import com.devcontext.common.error.ApiException;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.ports.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "devcontext.llm.provider", havingValue = "gemini")
public class GeminiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);

    private final DevContextLlmProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiLlmClient(DevContextLlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("Gemini LLM client initialized with model {}, base URL {}, and timeout {}",
                properties.gemini().model(),
                properties.gemini().baseUrl(),
                properties.gemini().timeout());
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        DevContextLlmProperties.Gemini gemini = properties.gemini();
        if (gemini.apiKey() == null || gemini.apiKey().isBlank()) {
            throw new ApiException("LLM_API_KEY_MISSING", "GEMINI_API_KEY is not configured", HttpStatus.BAD_REQUEST);
        }
        String modelName = configuredModel(request, gemini);
        HttpRequest httpRequest = buildRequest(gemini, modelName, request.prompt());
        log.info("Calling Gemini model {} via {} with timeout {}", modelName, trimTrailingSlash(gemini.baseUrl()), gemini.timeout());
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            log.info("Gemini response status {}", response.statusCode());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException("LLM_CALL_FAILED", summarizeGeminiError(response.body()), HttpStatus.BAD_GATEWAY);
            }
            String content = extractText(response.body());
            return new LlmResponse(
                    content,
                    modelName,
                    estimateTokens(request.prompt()),
                    estimateTokens(content)
            );
        } catch (IOException e) {
            log.warn("Gemini request failed before receiving a valid response: {}", e.getMessage());
            throw new ApiException("LLM_CALL_FAILED", "Gemini request failed: " + e.getMessage(), HttpStatus.BAD_GATEWAY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("LLM_CALL_FAILED", "Gemini request was interrupted", HttpStatus.BAD_GATEWAY);
        }
    }

    private HttpRequest buildRequest(DevContextLlmProperties.Gemini gemini, String modelName, String prompt) {
        String encodedModel = URLEncoder.encode(modelName, StandardCharsets.UTF_8).replace("+", "%20");
        String baseUrl = trimTrailingSlash(gemini.baseUrl());
        String requestBody = serialize(Map.of(
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", prompt))
                ))
        ));
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models/" + encodedModel + ":generateContent"))
                .timeout(gemini.timeout())
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", gemini.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    private String extractText(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new ApiException("LLM_RESPONSE_EMPTY", "Gemini response did not include candidates", HttpStatus.BAD_GATEWAY);
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        List<String> texts = new ArrayList<>();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                String text = part.path("text").asText("");
                if (!text.isBlank()) {
                    texts.add(text);
                }
            }
        }
        if (texts.isEmpty()) {
            throw new ApiException("LLM_RESPONSE_EMPTY", "Gemini response did not include text", HttpStatus.BAD_GATEWAY);
        }
        return String.join(System.lineSeparator(), texts);
    }

    private String configuredModel(LlmRequest request, DevContextLlmProperties.Gemini gemini) {
        if (request.modelName() != null && !request.modelName().isBlank() && !"mock-llm".equals(request.modelName())) {
            return request.modelName();
        }
        return gemini.model();
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize Gemini request", e);
        }
    }

    private String summarizeGeminiError(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = root.path("error").path("message").asText();
            if (!message.isBlank()) {
                return "Gemini request failed: " + message;
            }
        } catch (IOException ignored) {
            // Fall back to a compact raw-body summary below.
        }
        String compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 300) {
            compact = compact.substring(0, 300);
        }
        return compact.isBlank() ? "Gemini request failed" : "Gemini request failed: " + compact;
    }

    private String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }
}
