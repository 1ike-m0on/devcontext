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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "devcontext.llm.provider", havingValue = "deepseek")
public class DeepSeekLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekLlmClient.class);

    private final DevContextLlmProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DeepSeekLlmClient(DevContextLlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("DeepSeek LLM client initialized with model {}, base URL {}, and timeout {}",
                properties.deepseek().model(),
                properties.deepseek().baseUrl(),
                properties.deepseek().timeout());
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        DevContextLlmProperties.DeepSeek deepseek = properties.deepseek();
        if (deepseek.apiKey() == null || deepseek.apiKey().isBlank()) {
            throw new ApiException("LLM_API_KEY_MISSING", "DEEPSEEK_API_KEY is not configured", HttpStatus.BAD_REQUEST);
        }
        String modelName = configuredModel(request, deepseek);
        HttpRequest httpRequest = buildRequest(deepseek, modelName, request.prompt());
        log.info("Calling DeepSeek model {} via {} with timeout {}", modelName, trimTrailingSlash(deepseek.baseUrl()), deepseek.timeout());
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            log.info("DeepSeek response status {}", response.statusCode());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new ApiException("LLM_AUTH_FAILED", summarizeDeepSeekError(response.body()), HttpStatus.BAD_GATEWAY);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException("LLM_CALL_FAILED", summarizeDeepSeekError(response.body()), HttpStatus.BAD_GATEWAY);
            }
            String content = extractText(response.body());
            return new LlmResponse(
                    content,
                    modelName,
                    estimateTokens(request.prompt()),
                    estimateTokens(content)
            );
        } catch (IOException e) {
            log.warn("DeepSeek request failed before receiving a valid response: {}", e.getMessage());
            throw new ApiException("LLM_CALL_FAILED", "DeepSeek request failed: " + e.getMessage(), HttpStatus.BAD_GATEWAY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("LLM_CALL_FAILED", "DeepSeek request was interrupted", HttpStatus.BAD_GATEWAY);
        }
    }

    private HttpRequest buildRequest(DevContextLlmProperties.DeepSeek deepseek, String modelName, String prompt) {
        String requestBody = serialize(Map.of(
                "model", modelName,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", prompt
                )),
                "stream", false
        ));
        return HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(deepseek.baseUrl()) + "/chat/completions"))
                .timeout(deepseek.timeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + deepseek.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
    }

    private String extractText(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ApiException("LLM_RESPONSE_EMPTY", "DeepSeek response did not include choices", HttpStatus.BAD_GATEWAY);
        }
        String content = choices.get(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new ApiException("LLM_RESPONSE_EMPTY", "DeepSeek response did not include message content", HttpStatus.BAD_GATEWAY);
        }
        return content;
    }

    private String configuredModel(LlmRequest request, DevContextLlmProperties.DeepSeek deepseek) {
        if (request.modelName() != null && !request.modelName().isBlank() && !"mock-llm".equals(request.modelName())) {
            return request.modelName();
        }
        return deepseek.model();
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize DeepSeek request", e);
        }
    }

    private String summarizeDeepSeekError(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = root.path("error").path("message").asText();
            if (!message.isBlank()) {
                return "DeepSeek request failed: " + message;
            }
        } catch (IOException ignored) {
            // Fall back to a compact raw-body summary below.
        }
        String compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 300) {
            compact = compact.substring(0, 300);
        }
        return compact.isBlank() ? "DeepSeek request failed" : "DeepSeek request failed: " + compact;
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
