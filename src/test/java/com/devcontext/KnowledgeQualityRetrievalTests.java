package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
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
        "spring.datasource.url=jdbc:sqlite:target/devcontext-knowledge-quality-test.sqlite",
        "devcontext.llm.provider=test"
})
@AutoConfigureMockMvc
@Import(KnowledgeQualityRetrievalTests.TestLlmConfig.class)
class KnowledgeQualityRetrievalTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KnowledgeIndexApplicationService indexService;

    @Autowired
    private StubQualityLlmClient llmClient;

    @TempDir
    private Path knowledgeRoot;

    @Test
    void askUsesSpecificRequiredEvidenceCitationWhenTopKIsOne() throws Exception {
        createQualityFixture();
        long sourceId = indexSource();
        llmClient.reset();

        JsonNode searchJson = search(sourceId, "What is the benchmark p95 latency?", 1);
        JsonNode topResult = searchJson.path("results").get(0);
        assertThat(topResult.path("filePath").asText()).isEqualTo("reports/benchmark/load-test.md");
        assertThat(topResult.path("evidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("BENCHMARK"));
        assertThat(topResult.path("scoreReasons"))
                .anySatisfy(reason -> assertThat(reason.asText()).isEqualTo("required_evidence:BENCHMARK"))
                .anySatisfy(reason -> assertThat(reason.asText()).isEqualTo("source_reliability:primary"))
                .anySatisfy(reason -> assertThat(reason.asText()).isEqualTo("specific_engineering_evidence:benchmark_file"));

        JsonNode askJson = ask(sourceId, "What is the benchmark p95 latency?", 1);
        assertThat(askJson.path("answer").asText()).contains("120ms").contains("[S1]");
        assertThat(askJson.path("citations").get(0).path("filePath").asText())
                .isEqualTo("reports/benchmark/load-test.md");
        assertThat(askJson.path("evidenceEvaluation").path("status").asText()).isEqualTo("sufficient");
        assertThat(askJson.path("evidenceEvaluation").path("matchedRequiredEvidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("BENCHMARK"));
        assertThat(askJson.path("evidenceEvaluation").path("noAnswerRequired").asBoolean()).isFalse();
        assertThat(llmClient.callCount()).isEqualTo(1);
    }

    private void createQualityFixture() throws Exception {
        Path generatedDir = Files.createDirectories(knowledgeRoot.resolve(".ai/generated"));
        Files.writeString(generatedDir.resolve("performance-summary.md"), """
                # Performance Summary

                Performance latency throughput latency performance throughput latency.
                TODO placeholder summary for future measurement notes.
                """);
        Path benchmarkDir = Files.createDirectories(knowledgeRoot.resolve("reports/benchmark"));
        Files.writeString(benchmarkDir.resolve("load-test.md"), """
                # Load Test

                The benchmark report shows p95 latency is 120ms and p99 latency is 210ms.
                """);
    }

    private long indexSource() {
        KnowledgeSource source = indexService.createSource(new CreateKnowledgeSourceCommand(
                "knowledge quality evidence",
                knowledgeRoot.toString(),
                "markdown_dir"
        ));
        indexService.indexSource(source.id());
        return source.id();
    }

    private JsonNode search(long sourceId, String query, int topK) throws Exception {
        String response = mockMvc.perform(post("/api/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", query,
                                "sourceId", sourceId,
                                "topK", topK
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode ask(long sourceId, String query, int topK) throws Exception {
        String response = mockMvc.perform(post("/api/knowledge/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", query,
                                "sourceId", sourceId,
                                "topK", topK
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    @TestConfiguration
    static class TestLlmConfig {

        @Bean
        StubQualityLlmClient stubQualityLlmClient() {
            return new StubQualityLlmClient();
        }
    }

    static class StubQualityLlmClient implements LlmClient {

        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public LlmResponse chat(LlmRequest request) {
            callCount.incrementAndGet();
            return new LlmResponse(
                    "The benchmark p95 latency is 120ms. [S1]",
                    request.modelName(),
                    180,
                    32
            );
        }

        void reset() {
            callCount.set(0);
        }

        int callCount() {
            return callCount.get();
        }
    }
}
