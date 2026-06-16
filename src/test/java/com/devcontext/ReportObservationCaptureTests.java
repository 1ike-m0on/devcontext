package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devcontext.application.memory.ObservationCaptureService;
import com.devcontext.domain.memory.ObservationSourceType;
import com.devcontext.domain.memory.ReportObservationSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-report-observation-test.sqlite",
        "devcontext.llm.provider=mock",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
class ReportObservationCaptureTests {

    private static final String SECRET = "sk-test-secret-value-1234567890";

    static {
        try {
            Files.deleteIfExists(Path.of("target/devcontext-report-observation-test.sqlite"));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private ObservationCaptureService observationCaptureService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void capturesReportSummaryAsQueryableObservationWithoutSecrets() throws Exception {
        long projectId = 7001L;
        String reportRunId = "real-llm-acceptance-20260102-030405";
        Instant generatedAt = Instant.parse("2026-01-02T03:04:05Z");
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("caseCount", 3);
        metrics.put("completed", 1);
        metrics.put("failed", 1);
        metrics.put("skipped", 1);
        metrics.put("providerMessage", "timeout while using api-key=" + SECRET);

        observationCaptureService.captureReport(new ReportObservationSnapshot(
                projectId,
                ObservationSourceType.BENCHMARK_REPORT,
                reportRunId,
                "real-llm-manual-acceptance",
                "real-llm-manual-acceptance",
                "REAL_LLM_MANUAL_ACCEPTANCE",
                "deepseek",
                "deepseek-chat",
                "failed",
                "LLM_TIMEOUT",
                "Provider timed out with Authorization: Bearer " + SECRET,
                "target/real-llm-manual-acceptance/" + reportRunId + ".json",
                generatedAt,
                metrics
        ));

        String response = mockMvc.perform(get("/api/observations")
                        .param("projectId", String.valueOf(projectId))
                        .param("taskType", "REAL_LLM_MANUAL_ACCEPTANCE")
                        .param("lifecycle", "raw")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode observation = objectMapper.readTree(response).path("data").get(0);
        assertThat(observation.path("sourceType").asText()).isEqualTo("benchmark_report");
        assertThat(observation.path("sourceRecordId").asText()).isEqualTo(reportRunId);
        assertThat(observation.path("sourceKey").asText())
                .isEqualTo("benchmark_report:real-llm-manual-acceptance:" + reportRunId);
        assertThat(observation.path("sourceStatus").asText()).isEqualTo("failed");
        assertThat(observation.path("provider").asText()).isEqualTo("deepseek");
        assertThat(observation.path("modelName").asText()).isEqualTo("deepseek-chat");
        assertThat(observation.path("errorType").asText()).isEqualTo("LLM_TIMEOUT");
        assertThat(observation.path("errorMessageSummary").asText())
                .contains("Authorization: Bearer ***")
                .doesNotContain(SECRET);
        assertThat(observation.path("reportRunId").asText()).isEqualTo(reportRunId);
        assertThat(observation.path("reportPath").asText())
                .isEqualTo("target/real-llm-manual-acceptance/" + reportRunId + ".json");
        assertThat(observation.path("occurredAt").asText()).isEqualTo(generatedAt.toString());
        assertThat(observation.toString()).doesNotContain(SECRET);

        JsonNode metadata = objectMapper.readTree(observation.path("metadataJson").asText());
        assertThat(metadata.path("suite").asText()).isEqualTo("real-llm-manual-acceptance");
        assertThat(metadata.path("reportType").asText()).isEqualTo("real-llm-manual-acceptance");
        assertThat(metadata.path("status").asText()).isEqualTo("failed");
        assertThat(metadata.path("failureCategory").asText()).isEqualTo("LLM_TIMEOUT");
        assertThat(metadata.path("generatedAt").asText()).isEqualTo(generatedAt.toString());
        assertThat(metadata.path("messageSummary").asText())
                .contains("Authorization: Bearer ***")
                .doesNotContain(SECRET);
        assertThat(metadata.path("summaryMetrics").path("caseCount").asInt()).isEqualTo(3);
        assertThat(metadata.path("summaryMetrics").path("completed").asInt()).isEqualTo(1);
        assertThat(metadata.path("summaryMetrics").path("failed").asInt()).isEqualTo(1);
        assertThat(metadata.path("summaryMetrics").path("skipped").asInt()).isEqualTo(1);
        assertThat(metadata.path("summaryMetrics").path("providerMessage").asText())
                .contains("api-key=***")
                .doesNotContain(SECRET);
        assertThat(metadata.toString()).doesNotContain(SECRET);
    }
}
