package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devcontext.application.llm.LocalLlmSettingsStore;
import com.devcontext.application.llm.LlmRuntimeStatus;
import com.devcontext.application.llm.LlmSettingsApplicationService;
import com.devcontext.config.DevContextLlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

    @BeforeEach
    void cleanLocalConfig() throws IOException {
        deleteRecursively(LOCAL_CONFIG_ROOT);
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
}
