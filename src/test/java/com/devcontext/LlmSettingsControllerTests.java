package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devcontext.application.llm.LlmRuntimeStatus;
import com.devcontext.application.llm.LlmSettingsApplicationService;
import com.devcontext.config.DevContextLlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-llm-settings-test.sqlite",
        "devcontext.llm.provider=gemini",
        "devcontext.llm.gemini.api-key=gemini-secret-for-test",
        "devcontext.llm.gemini.model=gemini-test-model"
})
@AutoConfigureMockMvc
class LlmSettingsControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
        assertThat(StreamSupport.stream(data.path("supportedProviders").spliterator(), false)
                .map(provider -> provider.path("provider").asText())
                .toList())
                .containsExactly("mock", "gemini", "deepseek");
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
    void computesDeepSeekStatusWithoutExposingSecrets() {
        DevContextLlmProperties properties = new DevContextLlmProperties(
                "deepseek",
                null,
                null,
                new DevContextLlmProperties.DeepSeek("deepseek-secret-for-test", "deepseek-v4-pro", null, null)
        );
        LlmSettingsApplicationService service = new LlmSettingsApplicationService(properties, new LlmRuntimeStatus());

        LlmSettingsApplicationService.LlmSettingsStatus status = service.status();

        assertThat(status.provider()).isEqualTo("deepseek");
        assertThat(status.model()).isEqualTo("deepseek-v4-pro");
        assertThat(status.keyConfigured()).isTrue();
        assertThat(status.toString()).doesNotContain("deepseek-secret-for-test");
    }
}
