package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-health-test.sqlite",
        "devcontext.llm.provider=mock",
        "devcontext.llm.gemini.api-key=",
        "devcontext.llm.deepseek.api-key=",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
class HealthControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void exposesSafeRuntimeDiagnosticsWithoutLeakingSecrets() throws Exception {
        String response = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");

        assertThat(data.path("service").asText()).isEqualTo("DevContext");
        assertThat(data.path("status").asText()).isEqualTo("UP");
        assertThat(data.path("llmProvider").asText()).isEqualTo("mock");
        assertThat(data.path("llmModel").asText()).isEqualTo("mock-llm");
        assertThat(data.path("llmClient").asText()).isEqualTo("MockLlmClient");
        assertThat(data.path("geminiApiKeyConfigured").asBoolean()).isFalse();
        assertThat(data.path("geminiTimeout").asText()).isEqualTo("PT1M");
        assertThat(data.path("deepseekApiKeyConfigured").asBoolean()).isFalse();
        assertThat(data.path("deepseekTimeout").asText()).isEqualTo("PT2M");
        assertThat(data.path("vectorProvider").asText()).isEqualTo("jdbc");
        assertThat(data.has("geminiApiKey")).isFalse();
        assertThat(data.has("deepseekApiKey")).isFalse();
    }
}
