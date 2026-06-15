package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devcontext.adapters.llm.MockLlmClient;
import com.devcontext.application.llm.LlmConnectionCheckApplicationService;
import com.devcontext.application.llm.LlmErrorTypes;
import com.devcontext.application.llm.LocalLlmSettingsStore;
import com.devcontext.application.llm.LlmRuntimeStatus;
import com.devcontext.application.llm.LlmSettingsApplicationService;
import com.devcontext.common.error.ApiException;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.ports.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-llm-settings-test.sqlite",
        "devcontext.local-config-root=target/llm-settings-controller-test",
        "devcontext.llm.provider=gemini",
        "devcontext.llm.gemini.api-key=gemini-secret-for-test",
        "devcontext.llm.gemini.model=gemini-test-model"
})
@AutoConfigureMockMvc
class LlmSettingsControllerTests {

    private static final Path LOCAL_CONFIG_ROOT = Path.of("target/llm-settings-controller-test");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StubConnectionCheckLlmClient connectionCheckLlmClient;

    @BeforeEach
    void cleanLocalConfig() throws IOException {
        deleteRecursively(LOCAL_CONFIG_ROOT);
        connectionCheckLlmClient.reset();
    }

    @Test
    void exposesLlmSettingsWithoutReturningApiKeys() throws Exception {
        String response = mockMvc.perform(get("/api/settings/llm"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("gemini-secret-for-test");

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("provider").asText()).isEqualTo("gemini");
        assertThat(data.path("model").asText()).isEqualTo("gemini-test-model");
        assertThat(data.path("keyConfigured").asBoolean()).isTrue();
        assertThat(data.path("keyStatus").asText()).isEqualTo("configured");
        assertThat(data.path("lastCallStatus").asText()).isEqualTo("never_called");
        assertThat(data.path("restartRequired").asBoolean()).isFalse();
        assertThat(data.path("localConfigPath").asText()).contains("config");
        assertThat(StreamSupport.stream(data.path("supportedProviders").spliterator(), false)
                .map(provider -> provider.path("provider").asText())
                .toList())
                .containsExactly("mock", "gemini", "deepseek");
    }

    @Test
    void savesMockProviderToIgnoredLocalConfigAndReportsPendingRestart() throws Exception {
        String response = mockMvc.perform(put("/api/settings/llm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "mock",
                                  "model": "mock-from-ui"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("provider").asText()).isEqualTo("gemini");
        assertThat(data.path("model").asText()).isEqualTo("gemini-test-model");
        assertThat(data.path("restartRequired").asBoolean()).isTrue();
        assertThat(data.path("pending").path("provider").asText()).isEqualTo("mock");
        assertThat(data.path("pending").path("model").asText()).isEqualTo("mock-from-ui");
        assertThat(data.path("pending").path("keyStatus").asText()).isEqualTo("not_required");

        String config = Files.readString(LOCAL_CONFIG_ROOT.resolve("config/devcontext.local.yml"));
        assertThat(config).contains("provider: mock");
        assertThat(config).contains("model: mock-from-ui");
        assertThat(config).doesNotContain("gemini-secret-for-test");

        String getResponse = mockMvc.perform(get("/api/settings/llm"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(getResponse).path("data").path("pending").path("provider").asText())
                .isEqualTo("mock");
    }

    @Test
    void savesGeminiKeyWithoutReturningItInApiResponse() throws Exception {
        String newSecret = "gemini-new-secret-from-ui";

        String response = mockMvc.perform(put("/api/settings/llm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "gemini",
                                  "model": "gemini-from-ui",
                                  "geminiApiKey": "gemini-new-secret-from-ui"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain(newSecret);
        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("restartRequired").asBoolean()).isTrue();
        assertThat(data.path("pending").path("provider").asText()).isEqualTo("gemini");
        assertThat(data.path("pending").path("keyConfigured").asBoolean()).isTrue();
        assertThat(data.path("pending").path("keyStatus").asText()).isEqualTo("configured");

        String config = Files.readString(LOCAL_CONFIG_ROOT.resolve("config/devcontext.local.yml"));
        assertThat(config).contains("provider: gemini");
        assertThat(config).contains("model: gemini-from-ui");
        assertThat(config).contains("api-key: " + newSecret);
    }

    @Test
    void savesDeepSeekKeyWithoutReturningItInApiResponse() throws Exception {
        String newSecret = "deepseek-new-secret-from-ui";

        String response = mockMvc.perform(put("/api/settings/llm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "provider": "deepseek",
                                  "model": "deepseek-from-ui",
                                  "deepseekApiKey": "deepseek-new-secret-from-ui"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain(newSecret);
        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("restartRequired").asBoolean()).isTrue();
        assertThat(data.path("pending").path("provider").asText()).isEqualTo("deepseek");
        assertThat(data.path("pending").path("keyConfigured").asBoolean()).isTrue();

        String config = Files.readString(LOCAL_CONFIG_ROOT.resolve("config/devcontext.local.yml"));
        assertThat(config).contains("provider: deepseek");
        assertThat(config).contains("model: deepseek-from-ui");
        assertThat(config).contains("api-key: " + newSecret);
    }

    @Test
    void includesMaskedLlmStatusInHealthResponse() throws Exception {
        String response = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("gemini-secret-for-test");

        JsonNode llm = objectMapper.readTree(response).path("data").path("llm");
        assertThat(llm.path("provider").asText()).isEqualTo("gemini");
        assertThat(llm.path("model").asText()).isEqualTo("gemini-test-model");
        assertThat(llm.path("keyConfigured").asBoolean()).isTrue();
    }

    @Test
    void testsConfiguredProviderConnectionWithoutReturningApiKeys() throws Exception {
        String response = mockMvc.perform(post("/api/settings/llm/test"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("gemini-secret-for-test");

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("provider").asText()).isEqualTo("gemini");
        assertThat(data.path("model").asText()).isEqualTo("gemini-test-model");
        assertThat(data.path("success").asBoolean()).isTrue();
        assertThat(data.path("failureCategory").asText()).isEqualTo("none");
        assertThat(data.path("messageSummary").asText()).contains("succeeded");
        assertThat(data.path("keyConfigured").asBoolean()).isTrue();
        assertThat(data.path("keyStatus").asText()).isEqualTo("configured");
        assertThat(connectionCheckLlmClient.lastRequest().modelName()).isEqualTo("gemini-test-model");
    }

    @Test
    void classifiesConnectionCheckFailureWithoutReturningApiKeys() throws Exception {
        connectionCheckLlmClient.failWith(new ApiException(
                LlmErrorTypes.AUTH_FAILED,
                "Invalid API key gemini-secret-for-test",
                HttpStatus.UNAUTHORIZED
        ));

        String response = mockMvc.perform(post("/api/settings/llm/test"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("gemini-secret-for-test");

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("provider").asText()).isEqualTo("gemini");
        assertThat(data.path("model").asText()).isEqualTo("gemini-test-model");
        assertThat(data.path("success").asBoolean()).isFalse();
        assertThat(data.path("failureCategory").asText()).isEqualTo(LlmErrorTypes.AUTH_FAILED);
        assertThat(data.path("messageSummary").asText()).contains("[masked]");
        assertThat(data.path("keyConfigured").asBoolean()).isTrue();
        assertThat(data.path("keyStatus").asText()).isEqualTo("configured");
    }

    @Test
    void connectionCheckPassesForMockProviderWithoutKey() throws IOException {
        DevContextLlmProperties properties = new DevContextLlmProperties(
                "mock",
                new DevContextLlmProperties.Mock("mock-test-model"),
                null,
                null
        );
        Path serviceRoot = Path.of("target/llm-settings-mock-connection-test");
        deleteRecursively(serviceRoot);
        LlmRuntimeStatus runtimeStatus = new LlmRuntimeStatus();
        LlmSettingsApplicationService settingsService = new LlmSettingsApplicationService(
                properties,
                runtimeStatus,
                new LocalLlmSettingsStore(serviceRoot.toString())
        );
        LlmConnectionCheckApplicationService connectionCheckService = new LlmConnectionCheckApplicationService(
                settingsService,
                new MockLlmClient(runtimeStatus),
                properties
        );

        LlmConnectionCheckApplicationService.LlmConnectionCheckResult result = connectionCheckService.testConnection();

        assertThat(result.provider()).isEqualTo("mock");
        assertThat(result.model()).isEqualTo("mock-test-model");
        assertThat(result.success()).isTrue();
        assertThat(result.failureCategory()).isEqualTo("none");
        assertThat(result.keyConfigured()).isFalse();
        assertThat(result.keyStatus()).isEqualTo("not_required");
    }

    @Test
    void connectionCheckClassifiesMissingRealProviderKeyWithoutCallingClient() throws IOException {
        DevContextLlmProperties properties = new DevContextLlmProperties(
                "gemini",
                null,
                new DevContextLlmProperties.Gemini(null, "gemini-test-model", null, null),
                null
        );
        Path serviceRoot = Path.of("target/llm-settings-missing-key-connection-test");
        deleteRecursively(serviceRoot);
        LlmRuntimeStatus runtimeStatus = new LlmRuntimeStatus();
        LlmSettingsApplicationService settingsService = new LlmSettingsApplicationService(
                properties,
                runtimeStatus,
                new LocalLlmSettingsStore(serviceRoot.toString())
        );
        AtomicBoolean called = new AtomicBoolean(false);
        LlmClient failingClient = request -> {
            called.set(true);
            throw new IllegalStateException("should not call provider without a key");
        };
        LlmConnectionCheckApplicationService connectionCheckService = new LlmConnectionCheckApplicationService(
                settingsService,
                failingClient,
                properties
        );

        LlmConnectionCheckApplicationService.LlmConnectionCheckResult result = connectionCheckService.testConnection();

        assertThat(called.get()).isFalse();
        assertThat(result.provider()).isEqualTo("gemini");
        assertThat(result.success()).isFalse();
        assertThat(result.failureCategory()).isEqualTo(LlmErrorTypes.PROVIDER_NOT_CONFIGURED);
        assertThat(result.keyConfigured()).isFalse();
        assertThat(result.keyStatus()).isEqualTo("missing");
    }

    @Test
    void computesDeepSeekStatusWithoutExposingSecrets() throws IOException {
        DevContextLlmProperties properties = new DevContextLlmProperties(
                "deepseek",
                null,
                null,
                new DevContextLlmProperties.DeepSeek("deepseek-secret-for-test", "deepseek-v4-pro", null, null)
        );
        Path serviceRoot = Path.of("target/llm-settings-service-test");
        deleteRecursively(serviceRoot);
        LlmSettingsApplicationService service = new LlmSettingsApplicationService(
                properties,
                new LlmRuntimeStatus(),
                new LocalLlmSettingsStore(serviceRoot.toString())
        );

        LlmSettingsApplicationService.LlmSettingsStatus status = service.status();

        assertThat(status.provider()).isEqualTo("deepseek");
        assertThat(status.model()).isEqualTo("deepseek-v4-pro");
        assertThat(status.keyConfigured()).isTrue();
        assertThat(status.toString()).doesNotContain("deepseek-secret-for-test");
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    @TestConfiguration
    static class ConnectionCheckTestConfig {

        @Bean
        @Primary
        StubConnectionCheckLlmClient connectionCheckLlmClient() {
            return new StubConnectionCheckLlmClient();
        }
    }

    static class StubConnectionCheckLlmClient implements LlmClient {

        private RuntimeException failure;
        private LlmRequest lastRequest;

        @Override
        public LlmResponse chat(LlmRequest request) {
            lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return new LlmResponse("OK", request.modelName(), 1, 1);
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        LlmRequest lastRequest() {
            return lastRequest;
        }

        void reset() {
            failure = null;
            lastRequest = null;
        }
    }
}
