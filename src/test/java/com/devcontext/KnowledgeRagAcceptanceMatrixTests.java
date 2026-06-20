package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.domain.graph.ProjectGraphNode;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.domain.profile.ProjectProfile;
import com.devcontext.domain.profile.ProjectProfileFact;
import com.devcontext.domain.profile.ProjectProfileSourceReference;
import com.devcontext.domain.project.Project;
import com.devcontext.ports.graph.ProjectGraphRepository;
import com.devcontext.ports.llm.LlmClient;
import com.devcontext.ports.profile.ProjectProfileRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "devcontext.vector.provider=jdbc",
        "devcontext.llm.provider=test"
})
@AutoConfigureMockMvc
@Import(KnowledgeRagAcceptanceMatrixTests.TestLlmConfig.class)
class KnowledgeRagAcceptanceMatrixTests {

    private static final Path TEST_DATABASE = Path.of(
            "target",
            "devcontext-knowledge-rag-acceptance-matrix-" + System.nanoTime() + ".sqlite"
    );

    static {
        try {
            Files.deleteIfExists(TEST_DATABASE);
            Files.deleteIfExists(Path.of(TEST_DATABASE + "-shm"));
            Files.deleteIfExists(Path.of(TEST_DATABASE + "-wal"));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + TEST_DATABASE);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectApplicationService projectService;

    @Autowired
    private ProjectGraphRepository graphRepository;

    @Autowired
    private ProjectProfileRepository profileRepository;

    @Autowired
    private AcceptanceLlmClient llmClient;

    @TempDir
    private Path workspaceRoot;

    @Test
    void noLlmAcceptanceMatrixCoversSourceGroundedAskSearchTraceAndCoverage() throws Exception {
        Path projectRoot = Files.createDirectories(workspaceRoot.resolve("payment-service"));
        createPaymentFixture(projectRoot);
        Project project = projectService.createProject("knowledge-rag-acceptance", projectRoot.toString(), "main");
        seedProjectContext(project);
        long sourceId = createAndIndexSource(projectRoot);

        JsonNode coverage = getJson("/api/knowledge-sources/" + sourceId + "/coverage").path("data");
        assertThat(coverage.path("coverage").path("SERVICE_CODE").asInt()).isPositive();
        assertThat(coverage.path("coverage").path("SQL_SCHEMA").asInt()).isPositive();
        assertThat(coverage.path("coverage").path("CONFIG").asInt()).isPositive();
        assertThat(coverage.path("coverage").path("TEST").asInt()).isPositive();

        JsonNode graphProfileSearch = search(
                sourceId,
                "How does PaymentRoutingService choose provider routing?",
                4
        );
        JsonNode graphProfileTopResult = graphProfileSearch.path("results").get(0);
        assertThat(graphProfileTopResult.path("filePath").asText())
                .isEqualTo("src/main/java/com/acme/payments/PaymentRoutingService.java");
        assertThat(graphProfileTopResult.path("scoreReasons"))
                .anySatisfy(reason -> assertThat(reason.asText()).startsWith("project_graph_context:"))
                .anySatisfy(reason -> assertThat(reason.asText()).startsWith("project_profile_context:"));

        JsonNode implementationAsk = ask(
                sourceId,
                "Where is PaymentRoutingService implemented and how does it call PaymentProviderClient?",
                6
        );
        assertSourceGroundedAsk(
                implementationAsk,
                "implementation_detail",
                List.of(
                        "src/main/java/com/acme/payments/PaymentRoutingService.java",
                        "src/main/java/com/acme/payments/PaymentProviderClient.java"
                ),
                List.of("source_evidence_loop", "evidence_pack_only", "primary_source_only")
        );
        assertTraceAndObservation(implementationAsk, "implementation_detail", true);

        JsonNode databaseAsk = ask(
                sourceId,
                "How does JdbcPaymentRouteRepository map the payment_route SQL table to the PaymentRoute model?",
                8
        );
        assertSourceGroundedAsk(
                databaseAsk,
                "database_detail",
                List.of(
                        "src/main/resources/db/schema.sql",
                        "src/main/java/com/acme/payments/JdbcPaymentRouteRepository.java",
                        "src/main/java/com/acme/payments/PaymentRoute.java"
                ),
                List.of("SQL_SCHEMA", "MAPPER", "SERVICE_CODE")
        );

        JsonNode configAsk = ask(
                sourceId,
                "\u914d\u7f6e\u4e2d payment provider timeout \u548c api key \u662f\u600e\u4e48\u8bfb\u53d6\u7684\uff1f",
                6
        );
        assertSourceGroundedAsk(
                configAsk,
                "configuration_detail",
                List.of("src/main/resources/application.yml"),
                List.of("CONFIG", "SERVICE_CODE")
        );
        assertThat(configAsk.path("answer").asText())
                .doesNotContain("live-secret")
                .contains("PAYMENT_PROVIDER_API_KEY");

        JsonNode testAsk = ask(
                sourceId,
                "Where is the source for PaymentRoutingContractTests class and what contract does it verify?",
                6
        );
        assertSourceGroundedAsk(
                testAsk,
                "implementation_detail",
                List.of("src/test/java/com/acme/payments/PaymentRoutingContractTests.java"),
                List.of("TEST", "SERVICE_CODE")
        );
        assertThat(testAsk.path("citations").get(0).path("filePath").asText())
                .isEqualTo("src/test/java/com/acme/payments/PaymentRoutingContractTests.java");

        Path emptyRoot = Files.createDirectories(workspaceRoot.resolve("empty-service"));
        Files.writeString(emptyRoot.resolve("README.md"), "Generated overview only. No implementation evidence.");
        long emptySourceId = createAndIndexSource(emptyRoot);
        int callsBeforeMissingEvidence = llmClient.callCount();
        JsonNode missingEvidenceAsk = ask(
                emptySourceId,
                "Where is FraudScoringQuantumAdapter implemented and which source file owns it?",
                5
        );
        assertThat(missingEvidenceAsk.path("evidenceEvaluation").path("status").asText())
                .isEqualTo("insufficient_evidence");
        assertThat(missingEvidenceAsk.path("evidenceEvaluation").path("noAnswerRequired").asBoolean())
                .isTrue();
        assertThat(missingEvidenceAsk.path("answer").asText())
                .contains("Insufficient evidence")
                .doesNotContain("FraudScoringQuantumAdapter.java")
                .doesNotContain("src/main/java/com/acme");
        assertThat(missingEvidenceAsk.path("citations")).isEmpty();
        assertThat(llmClient.callCount()).isEqualTo(callsBeforeMissingEvidence);
    }

    private void assertSourceGroundedAsk(
            JsonNode askJson,
            String expectedIntent,
            List<String> requiredPaths,
            List<String> requiredCitationSignals
    ) throws Exception {
        assertThat(askJson.path("queryPlan").path("intent").asText()).isEqualTo(expectedIntent);
        assertThat(askJson.path("evidenceEvaluation").path("status").asText()).isEqualTo("sufficient");
        assertThat(askJson.path("evidenceEvaluation").path("answerGuardDecision").asText()).isEqualTo("supported");
        assertThat(askJson.path("answer").asText()).contains("Source evidence pack paths:");
        for (String requiredPath : requiredPaths) {
            assertThat(askJson.path("answer").asText()).contains(requiredPath);
            assertThat(askJson.path("citations"))
                    .anySatisfy(citation -> assertThat(citation.path("filePath").asText()).isEqualTo(requiredPath));
        }
        assertThat(askJson.path("citations"))
                .allSatisfy(citation -> {
                    assertThat(citation.path("filePath").asText())
                            .doesNotStartWith("docs/")
                            .doesNotStartWith(".ai/");
                    assertThat(citation.path("scoreReasons"))
                            .anySatisfy(reason -> assertThat(reason.asText()).isEqualTo("source_evidence_loop"))
                            .anySatisfy(reason -> assertThat(reason.asText()).isEqualTo("evidence_pack_only"))
                            .anySatisfy(reason -> assertThat(reason.asText()).isEqualTo("primary_source_only"));
                });
        for (String signal : requiredCitationSignals) {
            assertThat(askJson.path("citations"))
                    .anySatisfy(citation -> assertCitationSignal(citation, signal));
        }
    }

    private void assertTraceAndObservation(JsonNode askJson, String expectedIntent, boolean requireGraphProfileTrace) throws Exception {
        long runId = askJson.path("runId").asLong();
        long retrievalRecordId = askJson.path("retrievalRecordId").asLong();
        JsonNode runDetail = getJson("/api/knowledge/runs/" + runId).path("data");
        assertThat(runDetail.path("events"))
                .extracting(event -> event.path("eventType").asText())
                .contains(
                        "KNOWLEDGE_QUERY_PLAN_BUILT",
                        "KNOWLEDGE_SOURCE_EVIDENCE_LOOP_STARTED",
                        "KNOWLEDGE_SOURCE_EVIDENCE_LOOP_SUPPORTED",
                        "KNOWLEDGE_EVIDENCE_EVALUATED",
                        "KNOWLEDGE_ANSWER_GUARD_APPLIED"
                )
                .doesNotContain("KNOWLEDGE_DEEP_SCAN_STARTED");
        assertThat(runDetail.path("events"))
                .filteredOn(event -> "KNOWLEDGE_SOURCE_EVIDENCE_LOOP_SUPPORTED".equals(event.path("eventType").asText()))
                .first()
                .satisfies(event -> {
                    assertThat(event.path("outputSummary").asText()).contains("fallbackToLegacyRetrieval=false");
                    assertThat(event.path("outputSummary").asText()).contains("evidencePackOnly=true");
                    assertThat(event.path("outputSummary").asText()).contains("primarySourceOnly=true");
                });
        JsonNode record = runDetail.path("retrievalRecords").get(0);
        JsonNode resultJson = objectMapper.readTree(record.path("resultJson").asText());
        assertThat(resultJson.path("queryPlanTrace").path("intent").asText()).isEqualTo(expectedIntent);
        assertThat(resultJson.path("results")).isNotEmpty();
        assertThat(resultJson.path("results"))
                .anySatisfy(result -> assertThat(result.path("scoreReasons")).isNotEmpty());

        JsonNode retrievalObservations = getJson("/api/observations?retrievalId=" + retrievalRecordId).path("data");
        assertThat(retrievalObservations)
                .anySatisfy(observation -> {
                    assertThat(observation.path("sourceType").asText()).isEqualTo("retrieval_record");
                    assertThat(observation.path("runId").asLong()).isEqualTo(runId);
                    JsonNode metadata = readJson(observation.path("metadataJson").asText());
                    assertThat(metadata.path("queryPlanTrace").path("intent").asText()).isEqualTo(expectedIntent);
                    assertThat(metadata.path("topResults").get(0).path("scoreReasons")).isNotEmpty();
                    if (requireGraphProfileTrace) {
                        assertThat(metadata.path("topResults"))
                                .anySatisfy(result -> {
                                    List<String> reasons = jsonTexts(result.path("scoreReasons"));
                                    assertThat(reasons).anyMatch(reason -> reason.startsWith("project_graph_context:"));
                                    assertThat(reasons).anyMatch(reason -> reason.startsWith("project_profile_context:"));
                                });
                    }
                });

        JsonNode runObservations = getJson("/api/agent-runs/" + runId + "/observations").path("data");
        assertThat(runObservations)
                .anySatisfy(observation -> {
                    assertThat(observation.path("sourceType").asText()).isEqualTo("agent_event");
                    assertThat(observation.path("title").asText()).isEqualTo("KNOWLEDGE_EVIDENCE_EVALUATED");
                    assertThat(observation.path("summary").asText()).contains("guard=supported");
                });
    }

    private void assertCitationSignal(JsonNode citation, String expectedSignal) {
        List<String> reasons = jsonTexts(citation.path("scoreReasons"));
        List<String> evidenceTypes = jsonTexts(citation.path("evidenceTypes"));
        assertThat(StreamSupport.stream(citation.path("scoreReasons").spliterator(), false)
                .map(JsonNode::asText)
                .anyMatch(reason -> reason.equals(expectedSignal) || reason.contains(expectedSignal))
                || evidenceTypes.contains(expectedSignal))
                .isTrue();
        assertThat(reasons).isNotEmpty();
    }

    private JsonNode search(long sourceId, String query, int topK) throws Exception {
        return postJson("/api/knowledge/search", Map.of(
                "query", query,
                "sourceId", sourceId,
                "topK", topK
        )).path("data");
    }

    private JsonNode ask(long sourceId, String query, int topK) throws Exception {
        return postJson("/api/knowledge/ask", Map.of(
                "query", query,
                "sourceId", sourceId,
                "topK", topK
        )).path("data");
    }

    private long createAndIndexSource(Path root) throws Exception {
        JsonNode source = postJson("/api/knowledge-sources", Map.of(
                "name", "knowledge-rag-acceptance-" + root.getFileName(),
                "rootPath", root.toString(),
                "sourceType", "project_ai_docs"
        )).path("data");
        long sourceId = source.path("id").asLong();
        postJson("/api/knowledge-sources/" + sourceId + "/index", Map.of()).path("data");
        return sourceId;
    }

    private JsonNode postJson(String path, Object body) throws Exception {
        String response = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode getJson(String path) throws Exception {
        String response = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private List<String> jsonTexts(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private void seedProjectContext(Project project) {
        Instant now = Instant.now();
        graphRepository.replaceProjectGraph(
                project.id(),
                List.of(new ProjectGraphNode(
                        null,
                        project.id(),
                        "class",
                        "class:PaymentRoutingService",
                        "PaymentRoutingService",
                        "src/main/java/com/acme/payments/PaymentRoutingService.java",
                        "CODE_MAP",
                        "code_structure",
                        "primary",
                        now,
                        now
                )),
                List.of()
        );
        profileRepository.upsertByProjectId(new ProjectProfile(
                null,
                project.id(),
                "ready",
                "Payment routing service profile.",
                List.of(new ProjectProfileFact(
                        "module",
                        "PaymentRoutingService",
                        "Chooses provider routing using PaymentProviderProperties and PaymentProviderClient.",
                        List.of(new ProjectProfileSourceReference(
                                "src/main/java/com/acme/payments/PaymentRoutingService.java",
                                "SERVICE_CODE",
                                "implementation",
                                "primary"
                        ))
                )),
                List.of(),
                now,
                now,
                now
        ));
    }

    private void createPaymentFixture(Path root) throws IOException {
        Path sourceDir = Files.createDirectories(root.resolve("src/main/java/com/acme/payments"));
        Files.writeString(sourceDir.resolve("PaymentRoutingService.java"), """
                package com.acme.payments;

                public class PaymentRoutingService {
                    private final PaymentProviderClient providerClient;
                    private final PaymentProviderProperties properties;
                    private final JdbcPaymentRouteRepository repository;

                    public PaymentRoutingService(PaymentProviderClient providerClient, PaymentProviderProperties properties, JdbcPaymentRouteRepository repository) {
                        this.providerClient = providerClient;
                        this.properties = properties;
                        this.repository = repository;
                    }

                    public PaymentRoute routePayment(PurchaseOrder order) {
                        PaymentRoute route = repository.findRoute(order.merchantId(), properties.primaryProvider());
                        providerClient.authorize(route.providerCode(), order.amountInCents(), properties.timeoutMillis());
                        return route;
                    }
                }
                """);
        Files.writeString(sourceDir.resolve("PaymentProviderClient.java"), """
                package com.acme.payments;

                public class PaymentProviderClient {
                    public void authorize(String providerCode, long amountInCents, long timeoutMillis) {
                        if (providerCode == null || providerCode.isBlank()) {
                            throw new IllegalArgumentException("providerCode is required");
                        }
                    }
                }
                """);
        Files.writeString(sourceDir.resolve("PaymentProviderProperties.java"), """
                package com.acme.payments;

                public record PaymentProviderProperties(String primaryProvider, long timeoutMillis, String apiKeyEnv) {
                    public String apiKeyEnvironmentVariable() {
                        return apiKeyEnv == null ? "PAYMENT_PROVIDER_API_KEY" : apiKeyEnv;
                    }
                }
                """);
        Files.writeString(sourceDir.resolve("JdbcPaymentRouteRepository.java"), """
                package com.acme.payments;

                import java.sql.ResultSet;

                public class JdbcPaymentRouteRepository {
                    public PaymentRoute findRoute(Long merchantId, String providerCode) {
                        String sql = "select id, merchant_id, provider_code, enabled from payment_route where merchant_id = ? and provider_code = ?";
                        return map(null, sql);
                    }

                    private PaymentRoute map(ResultSet rs, String sql) {
                        return new PaymentRoute(42L, 1001L, "stripe", true, sql);
                    }
                }
                """);
        Files.writeString(sourceDir.resolve("PaymentRoute.java"), """
                package com.acme.payments;

                public record PaymentRoute(Long id, Long merchantId, String providerCode, boolean enabled, String sourceSql) {
                }
                """);
        Files.writeString(sourceDir.resolve("PurchaseOrder.java"), """
                package com.acme.payments;

                public record PurchaseOrder(Long merchantId, long amountInCents) {
                }
                """);

        Path dbDir = Files.createDirectories(root.resolve("src/main/resources/db"));
        Files.writeString(dbDir.resolve("schema.sql"), """
                create table payment_route (
                    id bigint primary key,
                    merchant_id bigint not null,
                    provider_code varchar(64) not null,
                    enabled boolean not null
                );

                create index idx_payment_route_merchant_provider
                    on payment_route(merchant_id, provider_code);
                """);
        Path mapperDir = Files.createDirectories(root.resolve("src/main/resources/mapper"));
        Files.writeString(mapperDir.resolve("PaymentRouteMapper.xml"), """
                <mapper namespace="com.acme.payments.PaymentRouteMapper">
                  <select id="findRoute" resultType="PaymentRoute">
                    select id, merchant_id, provider_code, enabled
                    from payment_route
                    where merchant_id = #{merchantId} and provider_code = #{providerCode}
                  </select>
                </mapper>
                """);
        Path resourcesDir = Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(resourcesDir.resolve("application.yml"), """
                payment:
                  provider:
                    primary: stripe
                    timeoutMillis: 2500
                    apiKeyEnv: PAYMENT_PROVIDER_API_KEY
                """);
        Path testDir = Files.createDirectories(root.resolve("src/test/java/com/acme/payments"));
        Files.writeString(testDir.resolve("PaymentRoutingContractTests.java"), """
                package com.acme.payments;

                import org.junit.jupiter.api.Test;

                class PaymentRoutingContractTests {
                    @Test
                    void routesThroughConfiguredProviderAndRepositoryMapping() {
                        PaymentProviderProperties properties = new PaymentProviderProperties("stripe", 2500, "PAYMENT_PROVIDER_API_KEY");
                        PaymentRoutingService service = new PaymentRoutingService(new PaymentProviderClient(), properties, new JdbcPaymentRouteRepository());
                        service.routePayment(new PurchaseOrder(1001L, 9000L));
                    }
                }
                """);
        Path docsDir = Files.createDirectories(root.resolve("docs"));
        Files.writeString(docsDir.resolve("payment-overview.md"), """
                # Payment overview

                LegacyPaymentService is only a generated-looking overview note and must not be used as primary evidence.
                """);
    }

    @TestConfiguration
    static class TestLlmConfig {

        @Bean
        AcceptanceLlmClient acceptanceLlmClient() {
            return new AcceptanceLlmClient();
        }
    }

    static class AcceptanceLlmClient implements LlmClient {

        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public LlmResponse chat(LlmRequest request) {
            callCount.incrementAndGet();
            String prompt = request.prompt();
            String answer;
            if (prompt.contains("application.yml") || prompt.contains("PaymentProviderProperties")) {
                answer = "The payment provider config reads primary provider, timeoutMillis, and the PAYMENT_PROVIDER_API_KEY environment-variable name from application.yml through PaymentProviderProperties. [S1][S2]";
            } else if (prompt.contains("PaymentRoutingContractTests")) {
                answer = "PaymentRoutingContractTests verifies that routing uses the configured provider and repository mapping. [S1]";
            } else if (prompt.contains("payment_route") || prompt.contains("JdbcPaymentRouteRepository")) {
                answer = "The payment_route table is defined in schema.sql and JdbcPaymentRouteRepository maps rows into PaymentRoute for merchant/provider lookup. [S1][S2][S3]";
            } else {
                answer = "PaymentRoutingService calls PaymentProviderClient after loading a PaymentRoute through JdbcPaymentRouteRepository. [S1][S2]";
            }
            return new LlmResponse(answer, request.modelName(), 240, 40);
        }

        int callCount() {
            return callCount.get();
        }
    }
}
