package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devcontext.application.knowledge.CreateKnowledgeSourceCommand;
import com.devcontext.application.knowledge.KnowledgeIndexApplicationService;
import com.devcontext.domain.knowledge.KnowledgeSource;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.ports.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-rag-evidence-evaluation-test.sqlite",
        "devcontext.llm.provider=test"
})
@AutoConfigureMockMvc
@Import(RagEvidenceEvaluationTests.TestLlmConfig.class)
class RagEvidenceEvaluationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KnowledgeIndexApplicationService indexService;

    @Autowired
    private StubEvidenceLlmClient llmClient;

    @TempDir
    private Path knowledgeRoot;

    @Test
    void sufficientRequiredEvidenceCallsLlmAndReturnsEvaluation() throws Exception {
        Files.writeString(knowledgeRoot.resolve("benchmark.md"), """
                # Benchmark Report

                Load test result: p95 latency is 120ms and p99 latency is 210ms.
                """);
        long sourceId = indexSource("benchmark evidence");
        llmClient.reset();

        JsonNode askJson = ask(sourceId, "What is the benchmark p95 latency?");

        assertThat(askJson.path("answer").asText()).contains("120ms").contains("[S1]");
        assertThat(askJson.path("evidenceEvaluation").path("status").asText()).isEqualTo("sufficient");
        assertThat(askJson.path("evidenceEvaluation").path("sufficient").asBoolean()).isTrue();
        assertThat(askJson.path("evidenceEvaluation").path("noAnswerRequired").asBoolean()).isFalse();
        assertThat(askJson.path("evidenceEvaluation").path("matchedRequiredEvidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("BENCHMARK"));
        assertThat(askJson.path("evidenceEvaluation").path("citationAssessments"))
                .anySatisfy(citation -> {
                    assertThat(citation.path("sourcePath").asText()).isEqualTo("benchmark.md");
                    assertThat(citation.path("sourceReliabilities"))
                            .anySatisfy(reliability -> assertThat(reliability.asText()).isEqualTo("primary"));
                    assertThat(citation.path("supportsRequiredEvidence").asBoolean()).isTrue();
                });
        assertThat(llmClient.callCount()).isEqualTo(1);

        JsonNode runJson = runDetail(askJson.path("runId").asLong());
        assertThat(runJson.path("events"))
                .extracting(event -> event.path("eventType").asText())
                .contains("KNOWLEDGE_EVIDENCE_EVALUATED", "LLM_CALLED");
    }

    @Test
    void missingRequiredEvidenceReturnsNoAnswerGuardAndSkipsLlm() throws Exception {
        Files.writeString(knowledgeRoot.resolve("ops.md"), """
                # Operations

                The service runs with Docker Compose and exposes health checks.
                """);
        long sourceId = indexSource("missing benchmark evidence");
        llmClient.reset();

        JsonNode askJson = ask(sourceId, "What is the benchmark p95 latency?");

        assertThat(askJson.path("answer").asText())
                .contains("Insufficient evidence")
                .contains("BENCHMARK")
                .contains("no-answer guard");
        assertThat(askJson.path("evidenceEvaluation").path("status").asText()).isEqualTo("insufficient_evidence");
        assertThat(askJson.path("evidenceEvaluation").path("sufficient").asBoolean()).isFalse();
        assertThat(askJson.path("evidenceEvaluation").path("noAnswerRequired").asBoolean()).isTrue();
        assertThat(askJson.path("evidenceEvaluation").path("missingRequiredEvidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("BENCHMARK"));
        assertThat(llmClient.callCount()).isZero();

        JsonNode runJson = runDetail(askJson.path("runId").asLong());
        assertThat(runJson.path("events"))
                .extracting(event -> event.path("eventType").asText())
                .contains("KNOWLEDGE_EVIDENCE_EVALUATED")
                .doesNotContain("PROMPT_BUILT", "LLM_CALLED");
    }

    private long indexSource(String name) {
        KnowledgeSource source = indexService.createSource(new CreateKnowledgeSourceCommand(
                name,
                knowledgeRoot.toString(),
                "markdown_dir"
        ));
        indexService.indexSource(source.id());
        return source.id();
    }

    private JsonNode ask(long sourceId, String query) throws Exception {
        String response = mockMvc.perform(post("/api/knowledge/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", query,
                                "sourceId", sourceId,
                                "topK", 5
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode runDetail(long runId) throws Exception {
        String response = mockMvc.perform(get("/api/knowledge/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    @TestConfiguration
    static class TestLlmConfig {

        @Bean
        StubEvidenceLlmClient stubEvidenceLlmClient() {
            return new StubEvidenceLlmClient();
        }
    }

    static class StubEvidenceLlmClient implements LlmClient {

        private final AtomicReference<LlmRequest> lastRequest = new AtomicReference<>();
        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public LlmResponse chat(LlmRequest request) {
            lastRequest.set(request);
            callCount.incrementAndGet();
            return new LlmResponse(
                    "The benchmark p95 latency is 120ms. [S1]",
                    request.modelName(),
                    180,
                    32
            );
        }

        void reset() {
            lastRequest.set(null);
            callCount.set(0);
        }

        int callCount() {
            return callCount.get();
        }
    }
}
