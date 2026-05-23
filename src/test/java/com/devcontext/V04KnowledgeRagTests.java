package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.ports.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
        "spring.datasource.url=jdbc:sqlite:target/devcontext-v04-test.sqlite",
        "devcontext.llm.provider=test"
})
@AutoConfigureMockMvc
@Import(V04KnowledgeRagTests.TestLlmConfig.class)
class V04KnowledgeRagTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StubKnowledgeLlmClient llmClient;

    @TempDir
    private Path knowledgeRoot;

    @Test
    void importsMarkdownRetrievesHybridResultsAndAnswersWithCitations() throws Exception {
        createKnowledgeFixture();

        String createSourceResponse = mockMvc.perform(post("/api/knowledge-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "v04 local docs",
                                "rootPath", knowledgeRoot.toString(),
                                "sourceType", "markdown_dir"
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode sourceJson = objectMapper.readTree(createSourceResponse).path("data");
        long sourceId = sourceJson.path("id").asLong();
        assertThat(sourceId).isPositive();
        assertThat(sourceJson.path("status").asText()).isEqualTo("created");

        String indexResponse = mockMvc.perform(post("/api/knowledge-sources/{sourceId}/index", sourceId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode indexJson = objectMapper.readTree(indexResponse).path("data");
        assertThat(indexJson.path("documentsIndexed").asInt()).isEqualTo(5);
        assertThat(indexJson.path("chunksIndexed").asInt()).isGreaterThanOrEqualTo(5);

        String searchResponse = mockMvc.perform(post("/api/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", "How do I start the service on port 18080?",
                                "sourceId", sourceId,
                                "topK", 5
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode searchJson = objectMapper.readTree(searchResponse).path("data");
        assertThat(searchJson.path("retrievalRecordId").asLong()).isPositive();
        assertThat(searchJson.path("rewrittenQuery").asText()).isEqualTo("How do I start the service on port 18080?");
        JsonNode results = searchJson.path("results");
        assertThat(results).isNotEmpty();
        assertThat(results)
                .anySatisfy(result -> {
                    assertThat(result.path("filePath").asText()).isEqualTo("dev-guide.md");
                    assertThat(result.path("keywordScore").asDouble()).isGreaterThan(0);
                    assertThat(result.path("vectorScore").asDouble()).isGreaterThan(0);
                    assertThat(result.path("fusedScore").asDouble()).isGreaterThan(0);
                });
        assertThat(results)
                .allSatisfy(result -> assertThat(isHeadingOnly(result.path("content").asText())).isFalse());

        String askResponse = mockMvc.perform(post("/api/knowledge/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", "How should DevContext run locally?",
                                "sourceId", sourceId,
                                "topK", 5
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode askJson = objectMapper.readTree(askResponse).path("data");
        long runId = askJson.path("runId").asLong();
        assertThat(runId).isPositive();
        assertThat(askJson.path("retrievalRecordId").asLong()).isPositive();
        assertThat(askJson.path("answer").asText()).contains("[S1]");
        assertThat(askJson.path("citations")).isNotEmpty();
        assertThat(llmClient.lastRequest().get().prompt())
                .contains("Retrieved context")
                .contains("dev-guide.md")
                .contains("server.port=18080")
                .contains("Cite sources with [S1], [S2] style references.");

        String runResponse = mockMvc.perform(get("/api/knowledge/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode runJson = objectMapper.readTree(runResponse).path("data");
        assertThat(runJson.path("retrievalRecords")).hasSize(1);
        assertThat(runJson.path("events"))
                .extracting(event -> event.path("eventType").asText())
                .containsExactly(
                        "RUN_STARTED",
                        "KNOWLEDGE_QUERY_REWRITTEN",
                        "KNOWLEDGE_RETRIEVED",
                        "PROMPT_BUILT",
                        "LLM_CALLED",
                        "RAG_ANSWER_GENERATED",
                        "RUN_FINISHED"
                );
    }

    private void createKnowledgeFixture() throws Exception {
        Files.writeString(knowledgeRoot.resolve("dev-guide.md"), """
                # Dev Guide

                ## Local Run

                DevContext runs with `mvn spring-boot:run`.
                The application uses `server.port=18080` for local API testing.
                """);
        Files.writeString(knowledgeRoot.resolve("api-design.md"), """
                # API Design

                ## Decision API

                Reuse advice is exposed through `/api/decisions/reuse-advice`.
                Knowledge RAG uses `/api/knowledge/search` and `/api/knowledge/ask`.
                """);
        Files.writeString(knowledgeRoot.resolve("pagination-decision.md"), """
                # Pagination Decision

                Use cursor pagination for append-heavy ledger pages.
                Deep offset pagination is avoided because it can become slow as data grows.
                """);
        Files.writeString(knowledgeRoot.resolve("pitfalls.md"), """
                # Pitfalls

                Watch for N+1 queries in list pages and avoid hidden external calls during tests.
                """);
        Files.writeString(knowledgeRoot.resolve("testing.md"), """
                # Testing

                Use `mvn test` for local verification.
                RAG tests should use fixture Markdown and a stub LLM client.
                """);
    }

    private boolean isHeadingOnly(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.matches("^#{1,6}\\s+.+")) {
                continue;
            }
            return false;
        }
        return true;
    }

    @TestConfiguration
    static class TestLlmConfig {

        @Bean
        StubKnowledgeLlmClient stubKnowledgeLlmClient() {
            return new StubKnowledgeLlmClient();
        }
    }

    static class StubKnowledgeLlmClient implements LlmClient {

        private final AtomicReference<LlmRequest> lastRequest = new AtomicReference<>();

        @Override
        public LlmResponse chat(LlmRequest request) {
            lastRequest.set(request);
            return new LlmResponse(
                    "Run DevContext with `mvn spring-boot:run` and use port 18080 for local API testing. [S1]",
                    request.modelName(),
                    240,
                    45
            );
        }

        AtomicReference<LlmRequest> lastRequest() {
            return lastRequest;
        }
    }
}
