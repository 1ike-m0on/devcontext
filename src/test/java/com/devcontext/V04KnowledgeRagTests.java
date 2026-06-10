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
        assertThat(indexJson.path("coverageReport").path("sourceId").asLong()).isEqualTo(sourceId);
        assertThat(indexJson.path("coverageReport").path("chunksIndexed").asInt()).isGreaterThanOrEqualTo(5);

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
        assertThat(searchJson.path("rewrittenQuery").asText())
                .contains("How do I start the service on port 18080?")
                .contains("start")
                .contains("service")
                .contains("port");
        assertThat(searchJson.path("queryPlan").path("preferredEvidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("DEPLOYMENT"));
        JsonNode results = searchJson.path("results");
        assertThat(results).isNotEmpty();
        assertThat(results)
                .anySatisfy(result -> {
                    assertThat(result.path("filePath").asText()).isEqualTo("dev-guide.md");
                    assertThat(result.path("keywordScore").asDouble()).isGreaterThan(0);
                    assertThat(result.path("vectorScore").asDouble()).isGreaterThan(0);
                    assertThat(result.path("fusedScore").asDouble()).isGreaterThan(0);
                    assertThat(result.path("evidenceTypes")).isNotEmpty();
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
        assertThat(askJson.path("queryPlan").path("noAnswerPolicy").asText()).isEqualTo("require_retrieved_context");
        assertThat(askJson.path("answer").asText()).contains("[S1]");
        assertThat(askJson.path("citations")).isNotEmpty();
        assertThat(llmClient.lastRequest().get().prompt())
                .contains("Retrieved context")
                .contains("Evidence plan")
                .contains("Evidence types")
                .contains("dev-guide.md")
                .contains("server.port=18080")
                .contains("Cite sources with [S1], [S2] style references.");

        String runResponse = mockMvc.perform(get("/api/knowledge/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode runJson = objectMapper.readTree(runResponse).path("data");
        assertThat(runJson.path("run").path("provider").asText()).isEqualTo("test");
        assertThat(runJson.path("run").path("modelName").asText()).isEqualTo("mock-llm");
        assertThat(runJson.path("retrievalRecords")).hasSize(1);
        long retrievalRecordId = askJson.path("retrievalRecordId").asLong();
        String retrievalObservationResponse = mockMvc.perform(get("/api/observations")
                        .param("retrievalId", String.valueOf(retrievalRecordId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode retrievalObservations = objectMapper.readTree(retrievalObservationResponse).path("data");
        AtomicReference<JsonNode> retrievalObservation = new AtomicReference<>();
        assertThat(retrievalObservations)
                .anySatisfy(observation -> {
                    assertThat(observation.path("sourceType").asText()).isEqualTo("retrieval_record");
                    assertThat(observation.path("sourceKey").asText()).isEqualTo("retrieval_record:" + retrievalRecordId);
                    assertThat(observation.path("runId").asLong()).isEqualTo(runId);
                    assertThat(observation.path("retrievalId").asLong()).isEqualTo(retrievalRecordId);
                    assertThat(observation.path("provider").asText()).isEqualTo("test");
                    retrievalObservation.set(observation);
                });
        JsonNode observationMetadata = objectMapper.readTree(retrievalObservation.get().path("metadataJson").asText());
        assertThat(observationMetadata.path("topResults")).isNotEmpty();
        assertThat(observationMetadata.path("topResults").get(0).path("evidenceTypes"))
                .isNotEmpty()
                .allSatisfy(type -> assertThat(type.asText()).matches("[A-Z_]+"));
        assertThat(runJson.path("events"))
                .extracting(event -> event.path("eventType").asText())
                .containsExactly(
                        "RUN_STARTED",
                        "KNOWLEDGE_QUERY_REWRITTEN",
                        "KNOWLEDGE_QUERY_PLAN_BUILT",
                        "KNOWLEDGE_RETRIEVED",
                        "KNOWLEDGE_EVIDENCE_RETRIEVED",
                        "PROMPT_BUILT",
                        "LLM_CALLED",
                        "RAG_ANSWER_GENERATED",
                        "RUN_FINISHED"
                );
        assertThat(runJson.path("events"))
                .filteredOn(event -> "LLM_CALLED".equals(event.path("eventType").asText()))
                .first()
                .satisfies(event -> assertThat(event.path("inputSummary").asText()).isEqualTo("test/mock-llm"));
    }

    @Test
    void indexesProjectEvidenceAndAnswersSqlIndexQuestions() throws Exception {
        createProjectEvidenceFixture();

        String createSourceResponse = mockMvc.perform(post("/api/knowledge-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "project evidence",
                                "rootPath", knowledgeRoot.toString(),
                                "sourceType", "project_ai_docs"
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long sourceId = objectMapper.readTree(createSourceResponse).path("data").path("id").asLong();

        String indexResponse = mockMvc.perform(post("/api/knowledge-sources/{sourceId}/index", sourceId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode indexJson = objectMapper.readTree(indexResponse).path("data");
        assertThat(indexJson.path("documentsIndexed").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(indexJson.path("chunksIndexed").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(indexJson.path("coverageReport").path("coverage").path("SQL_SCHEMA").asInt()).isPositive();
        assertThat(indexJson.path("coverageReport").path("coverage").path("CACHE").asInt()).isPositive();
        assertThat(indexJson.path("coverageReport").path("coverage").path("OBSERVABILITY").asInt()).isPositive();

        String coverageResponse = mockMvc.perform(get("/api/knowledge-sources/{sourceId}/coverage", sourceId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode coverageJson = objectMapper.readTree(coverageResponse).path("data");
        assertThat(coverageJson.path("coverage").path("SQL_SCHEMA").asInt()).isPositive();
        assertThat(coverageJson.path("coverage").path("OBSERVABILITY").asInt()).isPositive();

        String searchResponse = mockMvc.perform(post("/api/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", "sql里面具体用了什么索引",
                                "sourceId", sourceId,
                                "topK", 5
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode searchJson = objectMapper.readTree(searchResponse).path("data");
        assertThat(searchJson.path("rewrittenQuery").asText())
                .contains("sql")
                .contains("database")
                .contains("index");
        assertThat(searchJson.path("queryPlan").path("preferredEvidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("SQL_SCHEMA"));
        JsonNode results = searchJson.path("results");
        assertThat(results.get(0).path("filePath").asText()).isEqualTo("src/main/resources/db/schema.sql");
        assertThat(results.get(0).path("evidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("SQL_SCHEMA"));
        assertThat(results)
                .anySatisfy(result -> {
                    assertThat(result.path("filePath").asText()).isEqualTo("src/main/resources/db/schema.sql");
                    assertThat(result.path("content").asText()).contains("idx_voucher_order_user_id");
                });

        String askResponse = mockMvc.perform(post("/api/knowledge/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", "sql里面具体用了什么索引",
                                "sourceId", sourceId,
                                "topK", 5
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode askJson = objectMapper.readTree(askResponse).path("data");
        assertThat(askJson.path("answer").asText()).contains("idx_voucher_order_user_id");
        assertThat(askJson.path("citations")).isNotEmpty();
        assertThat(llmClient.lastRequest().get().prompt())
                .contains("required evidence")
                .contains("preferred evidence")
                .contains("src/main/resources/db/schema.sql")
                .contains("SQL_SCHEMA")
                .contains("idx_voucher_order_user_id")
                .contains("idx_voucher_order_voucher_status");

        String luaSearchResponse = mockMvc.perform(post("/api/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", "库存扣减 Lua 脚本在哪里",
                                "sourceId", sourceId,
                                "topK", 5
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode luaResults = objectMapper.readTree(luaSearchResponse).path("data").path("results");
        assertThat(luaResults.get(0).path("filePath").asText()).isEqualTo("src/main/resources/lua/seckill-stock.lua");
        assertThat(luaResults.get(0).path("evidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("CACHE"));
        assertThat(luaResults)
                .anySatisfy(result -> {
                    assertThat(result.path("filePath").asText()).isEqualTo("src/main/resources/lua/seckill-stock.lua");
                    assertThat(result.path("content").asText()).contains("redis.call");
                });

        String luaAskResponse = mockMvc.perform(post("/api/knowledge/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", "库存扣减 Lua 脚本在哪里",
                                "sourceId", sourceId,
                                "topK", 5
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode luaAskJson = objectMapper.readTree(luaAskResponse).path("data");
        assertThat(luaAskJson.path("answer").asText()).contains("seckill-stock.lua");
        assertThat(llmClient.lastRequest().get().prompt())
                .contains("src/main/resources/lua/seckill-stock.lua")
                .contains("redis.call");

        String japaneseLuaSearchResponse = mockMvc.perform(post("/api/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", "在庫を減らすLuaスクリプトはどこ",
                                "sourceId", sourceId,
                                "topK", 5
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode japaneseLuaSearchJson = objectMapper.readTree(japaneseLuaSearchResponse).path("data");
        assertThat(japaneseLuaSearchJson.path("rewrittenQuery").asText())
                .contains("stock-deduction")
                .contains("stock")
                .contains("lua")
                .contains("script");
        JsonNode japaneseLuaResults = japaneseLuaSearchJson.path("results");
        assertThat(japaneseLuaResults.get(0).path("filePath").asText()).isEqualTo("src/main/resources/lua/seckill-stock.lua");

        String monitoringSearchResponse = mockMvc.perform(post("/api/knowledge/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", "监控是怎么做的",
                                "sourceId", sourceId,
                                "topK", 5
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode monitoringSearchJson = objectMapper.readTree(monitoringSearchResponse).path("data");
        assertThat(monitoringSearchJson.path("rewrittenQuery").asText())
                .contains("monitoring")
                .contains("metrics")
                .contains("prometheus")
                .contains("grafana");
        assertThat(monitoringSearchJson.path("queryPlan").path("preferredEvidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("OBSERVABILITY"));
        JsonNode monitoringResults = monitoringSearchJson.path("results");
        assertThat(monitoringResults.get(0).path("filePath").asText().toLowerCase())
                .containsAnyOf("monitoring", "metrics", "deployment.md", "compose.yaml");
        assertThat(monitoringResults.get(0).path("evidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("OBSERVABILITY"));
        assertThat(monitoringResults)
                .anySatisfy(result -> assertThat(result.path("filePath").asText())
                        .isEqualTo("deploy/monitoring/prometheus.yml"));
        assertThat(monitoringResults)
                .anySatisfy(result -> assertThat(result.path("filePath").asText())
                        .isEqualTo("src/main/java/com/acme/metrics/OrderMetrics.java"));

        String monitoringAskResponse = mockMvc.perform(post("/api/knowledge/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", "监控是怎么做的",
                                "sourceId", sourceId,
                                "topK", 5
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode monitoringAskJson = objectMapper.readTree(monitoringAskResponse).path("data");
        assertThat(monitoringAskJson.path("answer").asText())
                .contains("Prometheus")
                .contains("Grafana")
                .contains("MeterRegistry");
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

    private void createProjectEvidenceFixture() throws Exception {
        Files.writeString(knowledgeRoot.resolve("AGENTS.md"), """
                # Project Guide

                This service handles flash-sale vouchers and voucher orders.
                """);
        Files.writeString(knowledgeRoot.resolve("DEPLOYMENT.md"), """
                # Deployment

                Monitoring is enabled through Spring Boot Actuator, Prometheus, and Grafana.
                Backend metrics are exposed at `http://localhost:8081/actuator/prometheus`.
                Run `docker compose --profile monitor up -d` to start Prometheus and Grafana.
                """);
        Files.writeString(knowledgeRoot.resolve("compose.yaml"), """
                services:
                  prometheus:
                    image: prom/prometheus
                    profiles: ["monitor"]
                    volumes:
                      - ./deploy/monitoring/prometheus.yml:/etc/prometheus/prometheus.yml:ro
                  grafana:
                    image: grafana/grafana
                    profiles: ["monitor"]
                    volumes:
                      - ./deploy/monitoring/grafana/dashboards:/var/lib/grafana/dashboards:ro
                """);
        Path aiDir = Files.createDirectories(knowledgeRoot.resolve(".ai"));
        Files.writeString(aiDir.resolve("code-map.json"), """
                {
                  "modules": ["voucher", "order"],
                  "summary": "Flash-sale voucher order service"
                }
                """);
        Path generatedDir = Files.createDirectories(aiDir.resolve("generated"));
        Files.writeString(generatedDir.resolve("dev-guide.md"), """
                # Dev Guide

                The project uses SQL, MyBatis mapper files, Redis Lua scripts, and generated project context documents.
                For exact SQL indexes or Lua commands, inspect implementation evidence rather than relying on this summary.
                """);
        Path dbDir = Files.createDirectories(knowledgeRoot.resolve("src/main/resources/db"));
        Files.writeString(dbDir.resolve("schema.sql"), """
                CREATE TABLE voucher_order (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    voucher_id BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP NOT NULL
                );

                CREATE INDEX idx_voucher_order_user_id
                    ON voucher_order(user_id);
                CREATE INDEX idx_voucher_order_voucher_status
                    ON voucher_order(voucher_id, status);
                """);
        Path mapperDir = Files.createDirectories(knowledgeRoot.resolve("src/main/resources/mapper"));
        Files.writeString(mapperDir.resolve("VoucherOrderMapper.xml"), """
                <mapper namespace="com.acme.order.VoucherOrderMapper">
                  <select id="findByUserId" resultType="VoucherOrder">
                    SELECT * FROM voucher_order WHERE user_id = #{userId}
                  </select>
                </mapper>
                """);
        Path luaDir = Files.createDirectories(knowledgeRoot.resolve("src/main/resources/lua"));
        Files.writeString(luaDir.resolve("seckill-stock.lua"), """
                -- stock deduction script
                local stockKey = KEYS[1]
                local stock = tonumber(redis.call('GET', stockKey) or '0')
                if stock <= 0 then
                  return 1
                end
                redis.call('DECR', stockKey)
                return 0
                """);
        Path repositoryDir = Files.createDirectories(knowledgeRoot.resolve("src/main/java/com/acme/order/repository"));
        Files.writeString(repositoryDir.resolve("VoucherOrderRepository.java"), """
                package com.acme.order.repository;

                public interface VoucherOrderRepository {
                    VoucherOrder findByUserId(Long userId);
                }
                """);
        Path metricsDir = Files.createDirectories(knowledgeRoot.resolve("src/main/java/com/acme/metrics"));
        Files.writeString(metricsDir.resolve("OrderMetrics.java"), """
                package com.acme.metrics;

                import io.micrometer.core.instrument.MeterRegistry;

                public class OrderMetrics {
                    private final MeterRegistry meterRegistry;

                    public OrderMetrics(MeterRegistry meterRegistry) {
                        this.meterRegistry = meterRegistry;
                    }

                    public void recordOrderCreated() {
                        meterRegistry.counter("life_order_created_total").increment();
                    }
                }
                """);
        Path monitoringDir = Files.createDirectories(knowledgeRoot.resolve("deploy/monitoring"));
        Files.writeString(monitoringDir.resolve("prometheus.yml"), """
                scrape_configs:
                  - job_name: life-service
                    metrics_path: /actuator/prometheus
                    static_configs:
                      - targets: ["backend:8081"]
                """);
        Path dashboardDir = Files.createDirectories(knowledgeRoot.resolve("deploy/monitoring/grafana/dashboards"));
        Files.writeString(dashboardDir.resolve("life-service-overview.json"), """
                {
                  "title": "Life Service Overview",
                  "panels": [
                    { "title": "Order throughput", "targets": [{ "expr": "life_order_created_total" }] }
                  ]
                }
                """);
        Path targetDir = Files.createDirectories(knowledgeRoot.resolve("target/generated"));
        Files.writeString(targetDir.resolve("ignored.sql"), "CREATE INDEX should_not_be_indexed ON ignored(id);");
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
            if (request.prompt().contains("seckill-stock.lua") || request.prompt().contains("redis.call")) {
                return new LlmResponse(
                        "库存扣减脚本位于 `src/main/resources/lua/seckill-stock.lua`，其中通过 `redis.call` 原子检查并扣减库存。 [S1]",
                        request.modelName(),
                        220,
                        42
                );
            }
            if (request.prompt().contains("idx_voucher_order_user_id")) {
                return new LlmResponse(
                        "SQL 中使用了 `idx_voucher_order_user_id` 和 `idx_voucher_order_voucher_status`，分别覆盖用户订单查询和代金券状态查询。 [S1]",
                        request.modelName(),
                        260,
                        52
                );
            }
            if (request.prompt().contains("prometheus.yml") || request.prompt().contains("MeterRegistry")) {
                return new LlmResponse(
                        "监控通过 Spring Boot Actuator 暴露 `/actuator/prometheus`，Prometheus 使用 `deploy/monitoring/prometheus.yml` 抓取指标，Grafana 读取 dashboard 配置；业务指标由 Micrometer `MeterRegistry` 记录。 [S1][S2]",
                        request.modelName(),
                        320,
                        68
                );
            }
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
