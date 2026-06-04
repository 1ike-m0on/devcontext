package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-v052-test.sqlite",
        "devcontext.llm.provider=mock",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
class V052DecisionGovernanceTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void governsDecisionCardsWithListDuplicateBatchStatusAndBatchReindex() throws Exception {
        String tag = "v052-governance-" + System.nanoTime();
        long firstDecisionId = createDecision(
                "V052 order ledger cursor pagination",
                "Order ledger records are append-heavy and slow when deep offset pagination scans many rows.",
                tag
        );
        long secondDecisionId = createDecision(
                "V052 payment ledger cursor pagination",
                "Payment ledger records are append-heavy and slow when deep offset pagination scans many rows.",
                tag
        );

        String listResponse = mockMvc.perform(get("/api/decisions")
                        .param("status", "active")
                        .param("tag", tag)
                        .param("query", "cursor"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode listed = objectMapper.readTree(listResponse).path("data");
        assertThat(listed)
                .extracting(node -> node.path("id").asLong())
                .contains(firstDecisionId, secondDecisionId);

        String duplicateResponse = mockMvc.perform(get("/api/decisions/duplicate-candidates")
                        .param("status", "active")
                        .param("tag", tag)
                        .param("minScore", "0.55"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode pairs = objectMapper.readTree(duplicateResponse).path("data").path("pairs");
        assertThat(pairs)
                .anySatisfy(pair -> {
                    assertThat(pair.path("score").asDouble()).isGreaterThanOrEqualTo(0.55);
                    assertThat(pair.path("reasons"))
                            .extracting(JsonNode::asText)
                            .contains("decision");
                });

        String batchStatusResponse = mockMvc.perform(patch("/api/decisions/batch-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decisionIds": [%d],
                                  "status": "deprecated"
                                }
                                """.formatted(firstDecisionId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode batchStatus = objectMapper.readTree(batchStatusResponse).path("data");
        long batchStatusRunId = batchStatus.path("runId").asLong();
        assertThat(batchStatus.path("updatedCount").asInt()).isEqualTo(1);
        assertThat(batchStatus.path("decisions").get(0).path("status").asText()).isEqualTo("deprecated");
        assertThat(batchStatus.path("decisions").get(0).path("embeddingStatus").asText()).isEqualTo("indexed");

        String batchStatusEventsResponse = mockMvc.perform(get("/api/agent-runs/{runId}/events", batchStatusRunId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(batchStatusEventsResponse).path("data"))
                .extracting(event -> event.path("eventType").asText())
                .contains("DECISION_BATCH_STATUS_CHANGED", "DECISION_INDEXED");

        String deprecatedListResponse = mockMvc.perform(get("/api/decisions")
                        .param("status", "deprecated")
                        .param("query", "cursor"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode deprecatedList = objectMapper.readTree(deprecatedListResponse).path("data");
        assertThat(deprecatedList)
                .extracting(node -> node.path("id").asLong())
                .contains(firstDecisionId)
                .doesNotContain(secondDecisionId);
        assertThat(deprecatedList)
                .allSatisfy(node -> assertThat(node.path("status").asText()).isEqualTo("deprecated"));

        String activeListResponse = mockMvc.perform(get("/api/decisions")
                        .param("status", "active")
                        .param("tag", tag))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode activeList = objectMapper.readTree(activeListResponse).path("data");
        assertThat(activeList)
                .extracting(node -> node.path("id").asLong())
                .contains(secondDecisionId)
                .doesNotContain(firstDecisionId);

        String reindexResponse = mockMvc.perform(post("/api/decisions/embeddings/rebuild")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "active",
                                  "tag": "%s"
                                }
                                """.formatted(tag)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode reindex = objectMapper.readTree(reindexResponse).path("data");
        long reindexRunId = reindex.path("runId").asLong();
        assertThat(reindex.path("requestedCount").asInt()).isEqualTo(1);
        assertThat(reindex.path("indexedCount").asInt()).isEqualTo(1);
        assertThat(reindex.path("failedCount").asInt()).isZero();
        assertThat(reindex.path("decisions").get(0).path("id").asLong()).isEqualTo(secondDecisionId);

        String reindexEventsResponse = mockMvc.perform(get("/api/agent-runs/{runId}/events", reindexRunId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(reindexEventsResponse).path("data"))
                .extracting(event -> event.path("eventType").asText())
                .contains("DECISION_BATCH_REINDEX_STARTED", "DECISION_INDEXED");
    }

    private long createDecision(String title, String scenario, String tag) throws Exception {
        String response = mockMvc.perform(post("/api/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "%s",
                                  "scenario": "%s",
                                  "options": ["offset pagination", "cursor pagination"],
                                  "decision": "Use cursor pagination with createdAt and id as a stable composite cursor.",
                                  "reasons": ["Avoids deep offset scans.", "Keeps repeated list reads stable while new rows arrive."],
                                  "tradeOffs": ["Cannot jump to arbitrary page numbers.", "Requires cursor encoding and validation."],
                                  "applicableWhen": ["Append-heavy ledger.", "Users browse records sequentially."],
                                  "notApplicableWhen": ["The product requires exact numbered page jumps."],
                                  "outcome": "Reduced performance risk for growing list pages.",
                                  "status": "active",
                                  "tags": ["%s"]
                                }
                                """.formatted(title, scenario, tag)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("decisionId").asLong();
    }
}
