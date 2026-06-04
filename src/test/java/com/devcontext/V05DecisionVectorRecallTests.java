package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.ports.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-v05-test.sqlite",
        "devcontext.llm.provider=test",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
@Import(V05DecisionVectorRecallTests.TestLlmConfig.class)
class V05DecisionVectorRecallTests {

    static {
        try {
            Files.deleteIfExists(Path.of("target/devcontext-v05-test.sqlite"));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StubDecisionLlmClient llmClient;

    @Test
    void recallsDecisionByVectorRebuildsEmbeddingAndRecordsFeedback() throws Exception {
        String createResponse = mockMvc.perform(post("/api/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "V05 order ledger cursor pagination decision",
                                  "scenario": "Order ledger records are append-heavy and slow when deep offset pagination scans many rows.",
                                  "options": ["offset pagination", "cursor pagination"],
                                  "decision": "Use cursor pagination with createdAt and id as a stable composite cursor.",
                                  "reasons": ["Avoids deep offset scans.", "Keeps repeated list reads stable while new orders arrive."],
                                  "tradeOffs": ["Cannot jump to arbitrary page numbers.", "Requires cursor encoding and validation."],
                                  "applicableWhen": ["Append-heavy order ledger.", "Users browse records sequentially."],
                                  "notApplicableWhen": ["The product requires exact numbered page jumps."],
                                  "outcome": "Reduced performance risk for growing order list pages.",
                                  "evidence": [
                                    {
                                      "type": "performance-test",
                                      "ref": "perf-2026-05-23-order-ledger",
                                      "summary": "Deep offset latency grew as order rows increased."
                                    }
                                  ],
                                  "status": "active",
                                  "tags": ["v05-vector-seed"]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long decisionId = objectMapper.readTree(createResponse).path("data").path("decisionId").asLong();
        assertThat(decisionId).isPositive();

        String detailResponse = mockMvc.perform(get("/api/decisions/{decisionId}", decisionId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode detailJson = objectMapper.readTree(detailResponse).path("data");
        assertThat(detailJson.path("embeddingStatus").asText()).isEqualTo("indexed");
        assertThat(detailJson.path("embeddingUpdatedAt").asText()).isNotBlank();

        String deprecatedResponse = mockMvc.perform(patch("/api/decisions/{decisionId}/status", decisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "deprecated"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode deprecatedJson = objectMapper.readTree(deprecatedResponse).path("data");
        long statusRunId = deprecatedJson.path("runId").asLong();
        assertThat(statusRunId).isPositive();
        assertThat(deprecatedJson.path("decision").path("status").asText()).isEqualTo("deprecated");

        String inactiveSearchResponse = mockMvc.perform(post("/api/decisions/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "How should an order ledger avoid slow deep offset scans while users browse sequential pages?",
                                  "tags": [],
                                  "topK": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(inactiveSearchResponse).path("data").path("matches"))
                .noneSatisfy(match -> assertThat(match.path("decision").path("id").asLong()).isEqualTo(decisionId));

        mockMvc.perform(patch("/api/decisions/{decisionId}/status", decisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "active"
                                }
                                """))
                .andExpect(status().isOk());

        String statusEventsResponse = mockMvc.perform(get("/api/agent-runs/{runId}/events", statusRunId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(statusEventsResponse).path("data"))
                .extracting(event -> event.path("eventType").asText())
                .contains("DECISION_STATUS_CHANGED", "DECISION_INDEXED");

        String searchResponse = mockMvc.perform(post("/api/decisions/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "How should an order ledger avoid slow deep offset scans while users browse sequential pages?",
                                  "tags": [],
                                  "topK": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode matches = objectMapper.readTree(searchResponse).path("data").path("matches");
        assertThat(matches)
                .anySatisfy(match -> {
                    assertThat(match.path("decision").path("id").asLong()).isEqualTo(decisionId);
                    assertThat(match.path("matchedTags")).isEmpty();
                    assertThat(match.path("vectorScore").asDouble()).isGreaterThan(0);
                    assertThat(match.path("matchReasons"))
                            .extracting(JsonNode::asText)
                            .contains("vector");
                });

        String rebuildResponse = mockMvc.perform(post("/api/decisions/{decisionId}/embedding", decisionId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(rebuildResponse).path("data").path("embeddingStatus").asText())
                .isEqualTo("indexed");

        String adviceResponse = mockMvc.perform(post("/api/decisions/reuse-advice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "For a payment history list, should I reuse the order ledger cursor pagination decision?",
                                  "tags": [],
                                  "topK": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode adviceJson = objectMapper.readTree(adviceResponse).path("data");
        long runId = adviceJson.path("runId").asLong();
        long reuseRecordId = adviceJson.path("reuseRecordId").asLong();
        assertThat(runId).isPositive();
        assertThat(reuseRecordId).isPositive();
        assertThat(llmClient.lastRequest().get().prompt())
                .contains("Score breakdown")
                .contains("vector=")
                .contains("V05 order ledger cursor pagination decision");

        String feedbackResponse = mockMvc.perform(patch("/api/decision-reuse-records/{recordId}/feedback", reuseRecordId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "partially_reused",
                                  "userFeedback": "Useful because the new list is also append-heavy and sequential."
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode feedbackJson = objectMapper.readTree(feedbackResponse).path("data");
        assertThat(feedbackJson.path("runId").asLong()).isEqualTo(runId);
        assertThat(feedbackJson.path("status").asText()).isEqualTo("partially_reused");
        assertThat(feedbackJson.path("accepted").asBoolean()).isTrue();
        assertThat(feedbackJson.path("userFeedback").asText()).contains("append-heavy");

        String compatibleFeedbackResponse = mockMvc.perform(patch("/api/decision-reuse-records/{recordId}/feedback", reuseRecordId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accepted": false,
                                  "userFeedback": "Keep as a rejected compatibility smoke test."
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode compatibleFeedbackJson = objectMapper.readTree(compatibleFeedbackResponse).path("data");
        assertThat(compatibleFeedbackJson.path("status").asText()).isEqualTo("rejected");
        assertThat(compatibleFeedbackJson.path("accepted").asBoolean()).isFalse();

        String eventsResponse = mockMvc.perform(get("/api/agent-runs/{runId}/events", runId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode events = objectMapper.readTree(eventsResponse).path("data");
        assertThat(events)
                .extracting(event -> event.path("eventType").asText())
                .contains(
                        "RUN_STARTED",
                        "DECISION_MEMORY_RECALLED",
                        "PROMPT_BUILT",
                        "LLM_CALLED",
                        "REUSE_ADVICE_SAVED",
                        "RUN_FINISHED",
                        "DECISION_REUSE_FEEDBACK_SAVED"
                );
    }

    @TestConfiguration
    static class TestLlmConfig {

        @Bean
        StubDecisionLlmClient stubDecisionLlmClient() {
            return new StubDecisionLlmClient();
        }
    }

    static class StubDecisionLlmClient implements LlmClient {

        private final AtomicReference<LlmRequest> lastRequest = new AtomicReference<>();

        @Override
        public LlmResponse chat(LlmRequest request) {
            lastRequest.set(request);
            return new LlmResponse("""
                    ## Recommendation
                    Reuse the cursor pagination decision only if the new list is append-heavy and read sequentially.
                    Verify numbered page jump requirements before adopting it.
                    """, request.modelName(), 320, 80);
        }

        AtomicReference<LlmRequest> lastRequest() {
            return lastRequest;
        }
    }
}
