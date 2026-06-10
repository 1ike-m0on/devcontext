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
        "spring.datasource.url=jdbc:sqlite:target/devcontext-mvp3-test.sqlite",
        "devcontext.llm.provider=test"
})
@AutoConfigureMockMvc
@Import(Mvp3DecisionMemoryTests.TestLlmConfig.class)
class Mvp3DecisionMemoryTests {

    static {
        try {
            Files.deleteIfExists(Path.of("target/devcontext-mvp3-test.sqlite"));
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
    void createsSearchesAndReusesDecisionMemoryWithAgentEvents() throws Exception {
        String createResponse = mockMvc.perform(post("/api/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "MVP3 invoice ledger cursor pagination decision",
                                  "scenario": "Invoice ledger records keep growing and deep offset pagination becomes slow.",
                                  "options": ["offset pagination", "cursor pagination"],
                                  "decision": "Use cursor pagination with stable createdAt and id ordering.",
                                  "reasons": ["Avoids deep offset scans.", "Keeps infinite scroll stable under new writes."],
                                  "tradeOffs": ["Cannot jump to arbitrary page numbers.", "Requires a stable composite cursor."],
                                  "applicableWhen": ["Large append-heavy lists.", "User browses sequentially."],
                                  "notApplicableWhen": ["Back office requires exact page number jumps."],
                                  "outcome": "Reduced slow query risk for long-running list pages.",
                                  "evidence": [
                                    {
                                      "type": "performance-test",
                                      "ref": "perf-2026-05-23-invoice-ledger",
                                      "summary": "Deep offset latency increased after the dataset grew."
                                    }
                                  ],
                                  "status": "active",
                                  "tags": ["mvp3-decision-test", "pagination", "performance"]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode createJson = objectMapper.readTree(createResponse).path("data");
        long decisionId = createJson.path("decisionId").asLong();
        assertThat(decisionId).isPositive();
        assertThat(createJson.path("status").asText()).isEqualTo("active");

        String detailResponse = mockMvc.perform(get("/api/decisions/{decisionId}", decisionId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode detailJson = objectMapper.readTree(detailResponse).path("data");
        assertThat(detailJson.path("evidence").get(0).path("ref").asText())
                .isEqualTo("perf-2026-05-23-invoice-ledger");

        String searchResponse = mockMvc.perform(post("/api/decisions/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "How should invoice ledger pagination handle growing data and performance?",
                                  "tags": ["mvp3-decision-test", "pagination"],
                                  "topK": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode matches = objectMapper.readTree(searchResponse).path("data").path("matches");
        assertThat(matches).isNotEmpty();
        assertThat(matches)
                .anySatisfy(match -> {
                    assertThat(match.path("decision").path("id").asLong()).isEqualTo(decisionId);
                    assertThat(match.path("score").asDouble()).isGreaterThan(0);
                    assertThat(match.path("matchedTags"))
                            .extracting(JsonNode::asText)
                            .contains("mvp3-decision-test", "pagination");
                });

        String adviceResponse = mockMvc.perform(post("/api/decisions/reuse-advice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "For a new order ledger page, should I reuse the invoice ledger pagination choice?",
                                  "tags": ["mvp3-decision-test", "pagination"],
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
        assertThat(adviceJson.path("advice").asText()).contains("Reuse Advice");
        assertThat(adviceJson.path("matchedDecisions"))
                .anySatisfy(match -> assertThat(match.path("decision").path("id").asLong()).isEqualTo(decisionId));
        assertThat(llmClient.lastRequest().get().prompt())
                .contains("MVP3 invoice ledger cursor pagination decision")
                .contains("perf-2026-05-23-invoice-ledger")
                .contains("should I reuse the invoice ledger pagination choice");

        String eventsResponse = mockMvc.perform(get("/api/agent-runs/{runId}/events", runId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode events = objectMapper.readTree(eventsResponse).path("data");
        assertThat(events)
                .extracting(event -> event.path("eventType").asText())
                .containsExactly(
                        "RUN_STARTED",
                        "DECISION_MEMORY_RECALLED",
                        "PROMPT_BUILT",
                        "LLM_CALLED",
                        "REUSE_ADVICE_SAVED",
                        "RUN_FINISHED"
                );

        mockMvc.perform(patch("/api/decision-reuse-records/{recordId}/feedback", reuseRecordId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "accepted",
                                  "accepted": true,
                                  "userFeedback": "Useful reuse advice for the new order ledger."
                                }
                                """))
                .andExpect(status().isOk());

        String feedbackObservationResponse = mockMvc.perform(get("/api/observations")
                        .param("runId", String.valueOf(runId))
                        .param("taskType", "DECISION_REUSE_FEEDBACK"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode feedbackObservations = objectMapper.readTree(feedbackObservationResponse).path("data");
        assertThat(feedbackObservations).hasSize(1);
        assertThat(feedbackObservations.get(0).path("sourceType").asText()).isEqualTo("decision_reuse_feedback");
        assertThat(feedbackObservations.get(0).path("sourceKey").asText())
                .startsWith("decision_reuse_feedback:" + reuseRecordId + ":accepted:true:");
        assertThat(feedbackObservations.get(0).path("decisionReuseRecordId").asLong()).isEqualTo(reuseRecordId);
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
                    ## Reuse Advice
                    The cursor pagination decision can transfer if the new order ledger is append-heavy and read sequentially.
                    The old decision should not be reused blindly when exact page jumps are required.
                    """, request.modelName(), 260, 90);
        }

        AtomicReference<LlmRequest> lastRequest() {
            return lastRequest;
        }
    }
}
