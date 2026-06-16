package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devcontext.domain.memory.Observation;
import com.devcontext.domain.memory.ObservationLifecycle;
import com.devcontext.domain.memory.ObservationSourceType;
import com.devcontext.ports.memory.ObservationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-observation-lifecycle-test.sqlite",
        "devcontext.llm.provider=mock",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
class ObservationLifecycleGovernanceTests {

    static {
        try {
            Files.deleteIfExists(Path.of("target/devcontext-observation-lifecycle-test.sqlite"));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void updatesObservationLifecycleAndKeepsLifecycleFilteringUsable() throws Exception {
        Observation saved = observationRepository.save(observation("raw", "lifecycle-update"));

        JsonNode classified = updateLifecycle(saved.id(), "classified");
        assertLifecycleUpdatePreservesObservationFields(classified, saved, "classified");
        assertThat(classified.path("updatedAt").asText()).isNotEqualTo(saved.updatedAt().toString());
        assertLifecycleQuery(saved.id(), "raw", false);
        assertLifecycleQuery(saved.id(), "classified", true);

        JsonNode candidate = updateLifecycle(saved.id(), "candidate");
        assertLifecycleUpdatePreservesObservationFields(candidate, saved, "candidate");
        assertLifecycleQuery(saved.id(), "classified", false);
        assertLifecycleQuery(saved.id(), "candidate", true);

        JsonNode archived = updateLifecycle(saved.id(), "archived");
        assertLifecycleUpdatePreservesObservationFields(archived, saved, "archived");
        assertLifecycleQuery(saved.id(), "candidate", false);
        assertLifecycleQuery(saved.id(), "archived", true);

        JsonNode raw = updateLifecycle(saved.id(), "raw");
        assertLifecycleUpdatePreservesObservationFields(raw, saved, "raw");
        assertLifecycleQuery(saved.id(), "archived", false);
        assertLifecycleQuery(saved.id(), "raw", true);
    }

    @Test
    void rejectsInvalidLifecycleWithStableErrorAndKeepsObservationUnchanged() throws Exception {
        Observation saved = observationRepository.save(observation("raw", "invalid-lifecycle"));

        String response = mockMvc.perform(patch("/api/observations/{observationId}/lifecycle", saved.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lifecycle": "published"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("errorCode").asText()).isEqualTo("OBSERVATION_LIFECYCLE_INVALID");
        assertThat(body.path("message").asText()).isEqualTo("Invalid observation lifecycle");

        Observation unchanged = observationRepository.findById(saved.id()).orElseThrow();
        assertThat(unchanged.lifecycle()).isEqualTo(ObservationLifecycle.RAW.value());
        assertThat(unchanged.updatedAt()).isEqualTo(saved.updatedAt());
    }

    private JsonNode updateLifecycle(Long observationId, String lifecycle) throws Exception {
        String response = mockMvc.perform(patch("/api/observations/{observationId}/lifecycle", observationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lifecycle": "%s"
                                }
                                """.formatted(lifecycle)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private void assertLifecycleQuery(Long observationId, String lifecycle, boolean expectedPresent) throws Exception {
        String response = mockMvc.perform(get("/api/observations")
                        .param("projectId", "42")
                        .param("taskType", "REPORT_ARTIFACT")
                        .param("lifecycle", lifecycle)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode observations = objectMapper.readTree(response).path("data");
        boolean present = false;
        for (JsonNode observation : observations) {
            if (observation.path("id").asLong() == observationId) {
                present = true;
                break;
            }
        }
        assertThat(present).as("observation %s present for lifecycle %s", observationId, lifecycle)
                .isEqualTo(expectedPresent);
    }

    private void assertLifecycleUpdatePreservesObservationFields(
            JsonNode updated,
            Observation original,
            String expectedLifecycle
    ) {
        assertThat(updated.path("id").asLong()).isEqualTo(original.id());
        assertThat(updated.path("projectId").asLong()).isEqualTo(original.projectId());
        assertThat(updated.path("sourceType").asText()).isEqualTo(original.sourceType());
        assertThat(updated.path("sourceRecordId").asText()).isEqualTo(original.sourceRecordId());
        assertThat(updated.path("sourceKey").asText()).isEqualTo(original.sourceKey());
        assertThat(updated.path("taskType").asText()).isEqualTo(original.taskType());
        assertThat(updated.path("lifecycle").asText()).isEqualTo(expectedLifecycle);
        assertThat(updated.path("sourceStatus").asText()).isEqualTo(original.sourceStatus());
        assertThat(updated.path("title").asText()).isEqualTo(original.title());
        assertThat(updated.path("summary").asText()).isEqualTo(original.summary());
        assertThat(updated.path("occurredAt").asText()).isEqualTo(original.occurredAt().toString());
        assertThat(updated.path("provider").asText()).isEqualTo(original.provider());
        assertThat(updated.path("modelName").asText()).isEqualTo(original.modelName());
        assertThat(updated.path("errorType").asText()).isEqualTo(original.errorType());
        assertThat(updated.path("errorMessageSummary").asText()).isEqualTo(original.errorMessageSummary());
        assertThat(updated.path("runId").asLong()).isEqualTo(original.runId());
        assertThat(updated.path("eventId").asLong()).isEqualTo(original.eventId());
        assertThat(updated.path("retrievalId").asLong()).isEqualTo(original.retrievalId());
        assertThat(updated.path("reviewId").asLong()).isEqualTo(original.reviewId());
        assertThat(updated.path("issueId").asLong()).isEqualTo(original.issueId());
        assertThat(updated.path("decisionReuseRecordId").asLong()).isEqualTo(original.decisionReuseRecordId());
        assertThat(updated.path("reportRunId").asText()).isEqualTo(original.reportRunId());
        assertThat(updated.path("reportPath").asText()).isEqualTo(original.reportPath());
        assertThat(updated.path("relationJson").asText()).isEqualTo(original.relationJson());
        assertThat(updated.path("metadataJson").asText()).isEqualTo(original.metadataJson());
        assertThat(updated.path("privacyLevel").asText()).isEqualTo(original.privacyLevel());
        assertThat(updated.path("createdAt").asText()).isEqualTo(original.createdAt().toString());
    }

    private Observation observation(String lifecycle, String runSuffix) {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant occurredAt = Instant.parse("2026-01-01T00:00:01Z");
        String reportRunId = "report-20260101-000001-" + runSuffix;
        return new Observation(
                null,
                42L,
                ObservationSourceType.BENCHMARK_REPORT.value(),
                reportRunId,
                "benchmark_report:sample-suite:" + reportRunId,
                "REPORT_ARTIFACT",
                lifecycle,
                "completed",
                "Report artifact: sample-suite",
                "sample-suite completed; caseCount=1",
                occurredAt,
                "mock",
                "mock-llm",
                "LLM_TIMEOUT",
                "Provider timed out with api-key=***",
                11L,
                12L,
                13L,
                14L,
                15L,
                16L,
                reportRunId,
                "target/sample/" + reportRunId + ".json",
                "{\"case\":\"sample\"}",
                "{\"caseCount\":1,\"completed\":1}",
                "sensitive_redacted",
                createdAt,
                createdAt
        );
    }
}
