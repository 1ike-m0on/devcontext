package com.devcontext.adapters.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devcontext.common.error.ApiException;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DeepSeekLlmClientTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOpenAiCompatibleChatCompletionRequestAndParsesResponse() throws Exception {
        server = startServer(exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");

            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            assertThat(request.path("model").asText()).isEqualTo("deepseek-chat");
            assertThat(request.path("messages").get(0).path("role").asText()).isEqualTo("user");
            assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo("review this diff");

            writeJson(exchange, 200, """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "review result"
                          }
                        }
                      ]
                    }
                    """);
        });

        DeepSeekLlmClient client = new DeepSeekLlmClient(properties(server), objectMapper);
        LlmResponse response = client.chat(new LlmRequest("review this diff", null));

        assertThat(response.content()).isEqualTo("review result");
        assertThat(response.modelName()).isEqualTo("deepseek-chat");
        assertThat(response.inputTokenEstimate()).isPositive();
        assertThat(response.outputTokenEstimate()).isPositive();
    }

    @Test
    void translatesProviderErrorsToApiException() throws Exception {
        server = startServer(exchange -> writeJson(exchange, 429, """
                {
                  "error": {
                    "message": "rate limit exceeded"
                  }
                }
                """));

        DeepSeekLlmClient client = new DeepSeekLlmClient(properties(server), objectMapper);

        assertThatThrownBy(() -> client.chat(new LlmRequest("review this diff", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("DeepSeek request failed: rate limit exceeded");
    }

    @Test
    void marksAuthenticationErrorsSeparately() throws Exception {
        server = startServer(exchange -> writeJson(exchange, 401, """
                {
                  "error": {
                    "message": "Authentication Fails, Your api key is invalid"
                  }
                }
                """));

        DeepSeekLlmClient client = new DeepSeekLlmClient(properties(server), objectMapper);

        assertThatThrownBy(() -> client.chat(new LlmRequest("review this diff", null)))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo("LLM_AUTH_FAILED"))
                .hasMessageContaining("Authentication Fails");
    }

    private HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
        return httpServer;
    }

    private DevContextLlmProperties properties(HttpServer httpServer) {
        String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
        return new DevContextLlmProperties(
                "deepseek",
                null,
                new DevContextLlmProperties.DeepSeek("test-key", "deepseek-chat", baseUrl, Duration.ofSeconds(5))
        );
    }

    private void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
