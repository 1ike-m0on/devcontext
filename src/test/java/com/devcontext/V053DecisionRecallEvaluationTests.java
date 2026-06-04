package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-v053-test.sqlite",
        "devcontext.llm.provider=mock",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
class V053DecisionRecallEvaluationTests {

    static {
        try {
            Files.deleteIfExists(Path.of("target/devcontext-v053-test.sqlite"));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void evaluatesDecisionRecallCasesAndRecordsRunEvents() throws Exception {
        String marker = "v053recall" + System.nanoTime();
        long cursorDecisionId = createDecision(
                "V053 " + marker + " cursor pagination decision",
                marker + " transaction ledger avoids deep offset scans with stable cursor pagination.",
                "Use cursor pagination with createdAt and id as a stable composite cursor.",
                marker
        );
        long cacheDecisionId = createDecision(
                "V053 " + marker + " product detail cache decision",
                marker + " product detail reads are repeated and should avoid unnecessary database hits.",
                "Use read-through cache with explicit invalidation for product detail snapshots.",
                marker
        );
        long deprecatedCursorDecisionId = createDecision(
                null,
                "deprecated",
                "V053 " + marker + " deprecated cursor pagination decision",
                marker + " transaction ledger avoids deep offset scans with stable cursor pagination.",
                "Use cursor pagination with createdAt and id as a stable composite cursor.",
                marker
        );

        String evaluationResponse = mockMvc.perform(post("/api/decisions/recall-evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cases": [
                                    {
                                      "name": "payment history cursor recall",
                                      "query": "For payment history %s, how should I avoid slow deep offset scans while browsing sequential pages?",
                                      "topK": 3,
                                      "expectedDecisionIds": [%d],
                                      "forbiddenDecisionIds": [%d]
                                    },
                                    {
                                      "name": "product detail cache recall",
                                      "query": "For product detail %s, how should I reduce repeated database reads with cache?",
                                      "topK": 3,
                                      "expectedDecisionIds": [%d]
                                    }
                                  ]
                                }
                                """.formatted(marker, cursorDecisionId, deprecatedCursorDecisionId, marker, cacheDecisionId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode result = objectMapper.readTree(evaluationResponse).path("data");
        long runId = result.path("runId").asLong();
        assertThat(runId).isPositive();
        assertThat(result.path("caseCount").asInt()).isEqualTo(2);
        assertThat(result.path("hitCount").asInt()).isEqualTo(2);
        assertThat(result.path("hitRate").asDouble()).isEqualTo(1.0);
        assertThat(result.path("meanReciprocalRank").asDouble()).isGreaterThanOrEqualTo(0.5);
        assertThat(result.path("averageRecallAtK").asDouble()).isEqualTo(1.0);
        assertThat(result.path("averagePrecisionAtK").asDouble()).isEqualTo(0.333);
        assertThat(result.path("forbiddenPassRate").asDouble()).isEqualTo(1.0);
        assertThat(result.path("averageFalsePositiveAtK").asDouble()).isGreaterThanOrEqualTo(0);
        assertThat(result.path("cases"))
                .allSatisfy(item -> {
                    assertThat(item.path("hit").asBoolean()).isTrue();
                    assertThat(item.path("forbiddenPass").asBoolean()).isTrue();
                    assertThat(item.path("firstHitRank").asInt()).isPositive();
                    assertThat(item.path("precisionAtK").asDouble()).isEqualTo(0.333);
                    assertThat(item.path("falsePositiveAtK").asDouble()).isGreaterThanOrEqualTo(0);
                    assertThat(item.path("hitDecisionIds")).isNotEmpty();
                    assertThat(item.path("missingExpectedDecisionIds")).isEmpty();
                    assertThat(item.path("forbiddenHitDecisionIds")).isEmpty();
                });

        String eventsResponse = mockMvc.perform(get("/api/agent-runs/{runId}/events", runId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(eventsResponse).path("data"))
                .extracting(event -> event.path("eventType").asText())
                .contains(
                        "DECISION_RECALL_CASE_EVALUATED",
                        "DECISION_RECALL_EVALUATION_SUMMARIZED",
                        "RUN_FINISHED"
                );
    }

    @Test
    void evaluatesProjectTagAndForbiddenDecisionFilters() throws Exception {
        String marker = "v053quality" + System.nanoTime();
        long expectedDecisionId = createDecision(
                501L,
                "active",
                "V053 " + marker + " project scoped cursor decision",
                marker + " scoped order ledger avoids deep offset scans with cursor pagination.",
                "Use cursor pagination with createdAt and id for scoped ledgers.",
                marker,
                "scope-a"
        );
        long wrongProjectDecisionId = createDecision(
                502L,
                "active",
                "V053 " + marker + " wrong project cursor decision",
                marker + " scoped order ledger avoids deep offset scans with cursor pagination.",
                "Use cursor pagination with createdAt and id for scoped ledgers.",
                marker,
                "scope-a"
        );
        long wrongTagDecisionId = createDecision(
                501L,
                "active",
                "V053 " + marker + " wrong tag cursor decision",
                marker + " scoped order ledger avoids deep offset scans with cursor pagination.",
                "Use cursor pagination with createdAt and id for scoped ledgers.",
                marker,
                "scope-b"
        );

        String evaluationResponse = mockMvc.perform(post("/api/decisions/recall-evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cases": [
                                    {
                                      "name": "project and tag scoped cursor recall",
                                      "query": "For scoped ledger %s, how should I avoid deep offset scans?",
                                      "projectId": 501,
                                      "tags": ["%s", "scope-a"],
                                      "topK": 5,
                                      "expectedDecisionIds": [%d],
                                      "forbiddenDecisionIds": [%d, %d]
                                    }
                                  ]
                                }
                                """.formatted(marker, marker, expectedDecisionId, wrongProjectDecisionId, wrongTagDecisionId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode result = objectMapper.readTree(evaluationResponse).path("data");
        JsonNode evaluationCase = result.path("cases").get(0);
        assertThat(result.path("hitRate").asDouble()).isEqualTo(1.0);
        assertThat(result.path("forbiddenPassRate").asDouble()).isEqualTo(1.0);
        assertThat(evaluationCase.path("returnedDecisionIds"))
                .extracting(JsonNode::asLong)
                .contains(expectedDecisionId)
                .doesNotContain(wrongProjectDecisionId, wrongTagDecisionId);
        assertThat(evaluationCase.path("forbiddenHitDecisionIds")).isEmpty();
    }

    private long createDecision(String title, String scenario, String decision, String tag) throws Exception {
        return createDecision(null, "active", title, scenario, decision, tag);
    }

    private long createDecision(Long projectId, String decisionStatus, String title, String scenario, String decision, String tag) throws Exception {
        return createDecision(projectId, decisionStatus, title, scenario, decision, tag, null);
    }

    private long createDecision(Long projectId, String decisionStatus, String title, String scenario, String decision, String tag, String secondTag) throws Exception {
        String projectField = projectId == null ? "" : "\"projectId\": %d,".formatted(projectId);
        String tags = secondTag == null || secondTag.isBlank()
                ? "\"%s\"".formatted(tag)
                : "\"%s\", \"%s\"".formatted(tag, secondTag);
        String response = mockMvc.perform(post("/api/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  %s
                                  "title": "%s",
                                  "scenario": "%s",
                                  "options": ["option A", "option B"],
                                  "decision": "%s",
                                  "reasons": ["Evidence-backed engineering trade-off."],
                                  "tradeOffs": ["Requires implementation discipline."],
                                  "applicableWhen": ["The same problem shape appears again."],
                                  "notApplicableWhen": ["The product constraints are different."],
                                  "outcome": "Improves future reuse quality.",
                                  "status": "%s",
                                  "tags": [%s]
                                }
                                """.formatted(projectField, title, scenario, decision, decisionStatus, tags)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("decisionId").asLong();
    }
}
