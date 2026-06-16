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
        "spring.datasource.url=jdbc:sqlite:target/devcontext-query-plan-contract-test.sqlite",
        "devcontext.llm.provider=test",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
@Import(KnowledgeQueryPlanContractTests.TestLlmConfig.class)
class KnowledgeQueryPlanContractTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StubQueryPlanLlmClient llmClient;

    @TempDir
    private Path projectRoot;

    @Test
    void searchReturnsStablePlanForMultilingualSqlIndexQuery() throws Exception {
        writeSqlIndexFixture();
        long sourceId = createAndIndexSource();

        JsonNode searchJson = search(sourceId, "SQL 里用了哪些索引 index?", 5);
        JsonNode queryPlan = searchJson.path("queryPlan");

        assertThat(queryPlan.path("intent").asText()).isEqualTo("database_detail");
        assertThat(queryPlan.path("originalQuery").asText()).isEqualTo("SQL 里用了哪些索引 index?");
        assertThat(queryPlan.path("rewrittenQuery").asText())
                .contains("SQL")
                .contains("sql")
                .contains("index");
        assertThat(queryPlan.path("preferredEvidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("SQL_SCHEMA"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("MAPPER"));
        assertThat(queryPlan.path("preferredSourceKinds"))
                .anySatisfy(kind -> assertThat(kind.asText()).isEqualTo("data_schema"))
                .anySatisfy(kind -> assertThat(kind.asText()).isEqualTo("data_access"))
                .anySatisfy(kind -> assertThat(kind.asText()).isEqualTo("source_code"));
        assertThat(queryPlan.path("forbiddenSourceKinds"))
                .anySatisfy(kind -> assertThat(kind.asText()).isEqualTo("documentation"));
        assertThat(queryPlan.path("fallbackStrategy").asText())
                .isEqualTo("retrieve_preferred_then_allow_partial_answer");
        assertThat(queryPlan.path("planningReasons"))
                .anySatisfy(reason -> assertThat(reason.asText()).isEqualTo("database_terms_detected"));

        assertThat(searchJson.path("results"))
                .anySatisfy(result -> assertThat(result.path("filePath").asText())
                        .isEqualTo("src/main/resources/db/schema.sql"));
    }

    @Test
    void askRecordsCompactQueryPlanTraceForObservabilityQuery() throws Exception {
        writeObservabilityFixture();
        long sourceId = createAndIndexSource();
        llmClient.reset();

        JsonNode askJson = ask(sourceId, "How is monitoring and observability done with metrics?", 5);
        JsonNode queryPlan = askJson.path("queryPlan");
        long runId = askJson.path("runId").asLong();
        long retrievalRecordId = askJson.path("retrievalRecordId").asLong();

        assertThat(runId).isPositive();
        assertThat(retrievalRecordId).isPositive();
        assertThat(queryPlan.path("intent").asText()).isEqualTo("observability_detail");
        assertThat(queryPlan.path("preferredEvidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("OBSERVABILITY"));
        assertThat(queryPlan.path("preferredSourceKinds"))
                .anySatisfy(kind -> assertThat(kind.asText()).isEqualTo("observability"))
                .anySatisfy(kind -> assertThat(kind.asText()).isEqualTo("deployment"))
                .anySatisfy(kind -> assertThat(kind.asText()).isEqualTo("configuration"))
                .anySatisfy(kind -> assertThat(kind.asText()).isEqualTo("source_code"));
        assertThat(queryPlan.path("forbiddenSourceKinds"))
                .anySatisfy(kind -> assertThat(kind.asText()).isEqualTo("documentation"));
        assertThat(queryPlan.path("planningReasons"))
                .anySatisfy(reason -> assertThat(reason.asText()).isEqualTo("observability_terms_detected"));
        assertThat(llmClient.lastRequest().get().prompt())
                .contains("Evidence plan")
                .contains("OBSERVABILITY");

        JsonNode runJson = getRun(runId);
        assertThat(runJson.path("events"))
                .filteredOn(event -> "KNOWLEDGE_QUERY_PLAN_BUILT".equals(event.path("eventType").asText()))
                .first()
                .satisfies(event -> assertThat(event.path("outputSummary").asText())
                        .contains("intent=observability_detail")
                        .contains("preferredSourceKinds=")
                        .contains("fallback=retrieve_preferred_then_allow_partial_answer"));

        JsonNode observationMetadata = retrievalObservationMetadata(retrievalRecordId);
        JsonNode trace = observationMetadata.path("queryPlanTrace");
        assertThat(trace.path("intent").asText()).isEqualTo("observability_detail");
        assertThat(trace.path("preferredEvidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("OBSERVABILITY"));
        assertThat(trace.path("preferredSourceKinds"))
                .anySatisfy(kind -> assertThat(kind.asText()).isEqualTo("observability"));
        assertThat(trace.path("fallbackStrategy").asText())
                .isEqualTo("retrieve_preferred_then_allow_partial_answer");
        assertThat(trace.path("planningReasons"))
                .anySatisfy(reason -> assertThat(reason.asText()).isEqualTo("observability_terms_detected"));
    }

    @Test
    void searchUsesGenericOverviewFallbackWhenNoSpecificIntentMatches() throws Exception {
        writeOverviewFixture();
        long sourceId = createAndIndexSource();

        JsonNode searchJson = search(sourceId, "Tell me about the project", 5);
        JsonNode queryPlan = searchJson.path("queryPlan");

        assertThat(queryPlan.path("intent").asText()).isEqualTo("project_overview");
        assertThat(queryPlan.path("preferredEvidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("MANUAL_DOC"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("GENERATED_DOC"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("CODE_MAP"));
        assertThat(queryPlan.path("preferredSourceKinds"))
                .anySatisfy(kind -> assertThat(kind.asText()).isEqualTo("documentation"))
                .anySatisfy(kind -> assertThat(kind.asText()).isEqualTo("code_structure"))
                .anySatisfy(kind -> assertThat(kind.asText()).isEqualTo("api_surface"))
                .anySatisfy(kind -> assertThat(kind.asText()).isEqualTo("source_code"));
        assertThat(queryPlan.path("requiredSourceKinds")).isEmpty();
        assertThat(queryPlan.path("forbiddenSourceKinds")).isEmpty();
        assertThat(queryPlan.path("fallbackStrategy").asText())
                .isEqualTo("retrieve_preferred_then_allow_partial_answer");
        assertThat(queryPlan.path("planningReasons"))
                .anySatisfy(reason -> assertThat(reason.asText()).isEqualTo("generic_overview_fallback"));
    }

    private long createAndIndexSource() throws Exception {
        String createSourceResponse = mockMvc.perform(post("/api/knowledge-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "query-plan-contract-source",
                                "rootPath", projectRoot.toString(),
                                "sourceType", "project_ai_docs"
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long sourceId = objectMapper.readTree(createSourceResponse).path("data").path("id").asLong();
        mockMvc.perform(post("/api/knowledge-sources/{sourceId}/index", sourceId))
                .andExpect(status().isOk());
        return sourceId;
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

    private JsonNode getRun(long runId) throws Exception {
        String response = mockMvc.perform(get("/api/knowledge/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode retrievalObservationMetadata(long retrievalRecordId) throws Exception {
        String response = mockMvc.perform(get("/api/observations")
                        .param("retrievalId", String.valueOf(retrievalRecordId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode observations = objectMapper.readTree(response).path("data");
        assertThat(observations).isNotEmpty();
        return objectMapper.readTree(observations.get(0).path("metadataJson").asText());
    }

    private void writeSqlIndexFixture() throws Exception {
        Path dbDir = Files.createDirectories(projectRoot.resolve("src/main/resources/db"));
        Files.writeString(dbDir.resolve("schema.sql"), """
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL
                );

                CREATE INDEX idx_orders_user_status ON orders(user_id, status);
                """);
        Path mapperDir = Files.createDirectories(projectRoot.resolve("src/main/resources/mapper"));
        Files.writeString(mapperDir.resolve("OrderMapper.xml"), """
                <mapper namespace="com.acme.order.OrderMapper">
                  <select id="findByUserId" resultType="Order">
                    SELECT * FROM orders WHERE user_id = #{userId}
                  </select>
                </mapper>
                """);
        Path serviceDir = Files.createDirectories(projectRoot.resolve("src/main/java/com/acme/order/service"));
        Files.writeString(serviceDir.resolve("OrderService.java"), """
                package com.acme.order.service;

                public class OrderService {
                    public void createOrder() {
                    }
                }
                """);
    }

    private void writeObservabilityFixture() throws Exception {
        Files.writeString(projectRoot.resolve("compose.yaml"), """
                services:
                  prometheus:
                    image: prom/prometheus
                    volumes:
                      - ./deploy/monitoring/prometheus.yml:/etc/prometheus/prometheus.yml:ro
                """);
        Path configDir = Files.createDirectories(projectRoot.resolve("src/main/resources"));
        Files.writeString(configDir.resolve("application.yml"), """
                management:
                  endpoints:
                    web:
                      exposure:
                        include: prometheus,health,info
                """);
        Path monitoringDir = Files.createDirectories(projectRoot.resolve("deploy/monitoring"));
        Files.writeString(monitoringDir.resolve("prometheus.yml"), """
                scrape_configs:
                  - job_name: order-service
                    metrics_path: /actuator/prometheus
                    static_configs:
                      - targets: ["backend:8080"]
                """);
        Path metricsDir = Files.createDirectories(projectRoot.resolve("src/main/java/com/acme/metrics"));
        Files.writeString(metricsDir.resolve("OrderMetrics.java"), """
                package com.acme.metrics;

                import io.micrometer.core.instrument.MeterRegistry;

                public class OrderMetrics {
                    public void record(MeterRegistry registry) {
                        registry.counter("orders_created_total").increment();
                    }
                }
                """);
    }

    private void writeOverviewFixture() throws Exception {
        Files.writeString(projectRoot.resolve("README.md"), """
                # Order Platform

                The project coordinates order intake and local developer workflows.
                """);
        Path manualDir = Files.createDirectories(projectRoot.resolve(".ai/manual"));
        Files.writeString(manualDir.resolve("business-context.md"), """
                # Business Context

                Human-written notes explain the order domain and team terminology.
                """);
        Path generatedDir = Files.createDirectories(projectRoot.resolve(".ai/generated"));
        Files.writeString(generatedDir.resolve("tech-architecture.md"), """
                # Generated Architecture

                Generated context summarizes modules and known implementation areas.
                """);
        Files.writeString(projectRoot.resolve(".ai/code-map.json"), """
                {
                  "modules": ["order"]
                }
                """);
        Path controllerDir = Files.createDirectories(projectRoot.resolve("src/main/java/com/acme/order/controller"));
        Files.writeString(controllerDir.resolve("OrderController.java"), """
                package com.acme.order.controller;

                public class OrderController {
                    public void listOrders() {
                    }
                }
                """);
        Path serviceDir = Files.createDirectories(projectRoot.resolve("src/main/java/com/acme/order/service"));
        Files.writeString(serviceDir.resolve("OrderService.java"), """
                package com.acme.order.service;

                public class OrderService {
                    public void listOrders() {
                    }
                }
                """);
    }

    @TestConfiguration
    static class TestLlmConfig {

        @Bean
        StubQueryPlanLlmClient stubQueryPlanLlmClient() {
            return new StubQueryPlanLlmClient();
        }
    }

    static class StubQueryPlanLlmClient implements LlmClient {

        private final AtomicReference<LlmRequest> lastRequest = new AtomicReference<>();

        @Override
        public LlmResponse chat(LlmRequest request) {
            lastRequest.set(request);
            return new LlmResponse(
                    "Monitoring uses Prometheus, application.yml actuator settings, and MeterRegistry metrics. [S1]",
                    request.modelName(),
                    120,
                    24
            );
        }

        AtomicReference<LlmRequest> lastRequest() {
            return lastRequest;
        }

        void reset() {
            lastRequest.set(null);
        }
    }
}
