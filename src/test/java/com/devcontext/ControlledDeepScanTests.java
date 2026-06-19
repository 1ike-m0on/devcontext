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
import java.util.List;
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
        "spring.datasource.url=jdbc:sqlite:target/devcontext-controlled-deep-scan-test.sqlite",
        "devcontext.llm.provider=test",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
@Import(ControlledDeepScanTests.TestLlmConfig.class)
class ControlledDeepScanTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KnowledgeIndexApplicationService indexService;

    @Autowired
    private StubDeepScanLlmClient llmClient;

    @TempDir
    private Path projectRoot;

    @Test
    void implementationQuestionUsesSourceEvidenceLoopPrimaryEvidence() throws Exception {
        writeGeneratedImplementationSummary();
        String serviceFile = "src/main/java/com/acme/order/OrderService.java";
        writeProjectFile(serviceFile, """
                package com.acme.order;

                import org.springframework.stereotype.Service;

                @Service
                public class OrderService {
                    public Order createOrder(CreateOrderCommand command) {
                        validate(command);
                        return new Order(command.userId(), command.sku());
                    }

                    private void validate(CreateOrderCommand command) {
                        if (command.userId() == null) {
                            throw new IllegalArgumentException("userId is required");
                        }
                    }
                }
                """);
        writeCodeMap(List.of(Map.of(
                "path", serviceFile,
                "kind", "source",
                "language", "Java",
                "module", "order",
                "roles", List.of("service", "spring-component")
        )), List.of(Map.of(
                "name", "OrderService",
                "role", "service",
                "module", "order",
                "file", serviceFile,
                "methods", List.of("createOrder", "validate")
        )));
        long sourceId = indexMarkdownSource("deep scan implementation source");
        llmClient.reset();

        JsonNode askJson = ask(sourceId, "How is the OrderService implementation structured?");

        assertThat(askJson.path("answer").asText()).contains("source evidence pack").contains("[S");
        assertThat(askJson.path("evidenceEvaluation").path("answerGuardDecision").asText()).isEqualTo("supported");
        assertThat(askJson.path("evidenceEvaluation").path("sufficient").asBoolean()).isTrue();
        assertThat(askJson.path("evidenceEvaluation").path("matchedPreferredEvidenceTypes"))
                .anySatisfy(type -> assertThat(type.asText()).isEqualTo("SERVICE_CODE"));
        assertThat(askJson.path("citations"))
                .anySatisfy(citation -> {
                    assertThat(citation.path("filePath").asText()).isEqualTo(serviceFile);
                    assertThat(citation.path("sourceName").asText()).isEqualTo("source-evidence-loop");
                    assertThat(citation.path("chunkId").isNull()).isTrue();
                    assertThat(citation.path("documentId").isNull()).isTrue();
                    assertThat(citation.path("evidenceTypes"))
                            .anySatisfy(type -> assertThat(type.asText()).isEqualTo("SERVICE_CODE"));
                    assertThat(citation.path("scoreReasons"))
                            .anySatisfy(reason -> assertThat(reason.asText()).isEqualTo("source_evidence_loop"))
                            .anySatisfy(reason -> assertThat(reason.asText()).isEqualTo("evidence_pack_only"))
                            .anySatisfy(reason -> assertThat(reason.asText()).isEqualTo("primary_source_only"));
                });
        assertThat(llmClient.callCount()).isEqualTo(1);

        JsonNode events = runEvents(askJson.path("runId").asLong());
        assertThat(events)
                .extracting(event -> event.path("eventType").asText())
                .contains(
                        "KNOWLEDGE_SOURCE_EVIDENCE_LOOP_STARTED",
                        "KNOWLEDGE_SOURCE_EVIDENCE_LOOP_SUPPORTED",
                        "KNOWLEDGE_ANSWER_GUARD_APPLIED",
                        "LLM_CALLED"
                )
                .doesNotContain(
                        "KNOWLEDGE_DEEP_SCAN_STARTED",
                        "KNOWLEDGE_DEEP_SCAN_FINISHED",
                        "KNOWLEDGE_DEEP_SCAN_EVIDENCE_EVALUATED"
                );
        assertThat(eventOutput(events, "KNOWLEDGE_SOURCE_EVIDENCE_LOOP_SUPPORTED"))
                .contains("fallbackToLegacyRetrieval=false")
                .contains("evidencePackOnly=true")
                .contains("primarySourceOnly=true");
    }

    @Test
    void missingGraphAndCodeMapCandidatesSkipsDeepScan() throws Exception {
        Path generatedDir = Files.createDirectories(projectRoot.resolve(".ai/generated"));
        Files.writeString(generatedDir.resolve("performance-summary.md"), """
                # Generated Performance Summary

                TODO placeholder: benchmark p95 latency might be 120ms after future load testing.
                """);
        long sourceId = indexMarkdownSource("deep scan skipped benchmark source");
        llmClient.reset();

        JsonNode askJson = ask(sourceId, "What is the benchmark p95 latency?");

        assertThat(askJson.path("answer").asText()).contains("Partial evidence");
        assertThat(askJson.path("evidenceEvaluation").path("answerGuardDecision").asText()).isEqualTo("partial");
        assertThat(llmClient.callCount()).isZero();

        JsonNode events = runEvents(askJson.path("runId").asLong());
        assertThat(events)
                .extracting(event -> event.path("eventType").asText())
                .contains("KNOWLEDGE_DEEP_SCAN_SKIPPED")
                .doesNotContain("KNOWLEDGE_DEEP_SCAN_STARTED", "KNOWLEDGE_DEEP_SCAN_FINISHED");
        assertThat(eventOutput(events, "KNOWLEDGE_DEEP_SCAN_SKIPPED"))
                .contains("no_project_graph_or_code_map_candidates")
                .contains("code_map_missing");
    }

    @Test
    void implementationQuestionUsesSourceEvidenceLoopInsteadOfBudgetedDeepScan() throws Exception {
        writeGeneratedImplementationSummary();
        String serviceFile = "src/main/java/com/acme/order/BudgetedOrderService.java";
        writeProjectFile(serviceFile, largeServiceSource());
        writeCodeMap(List.of(Map.of(
                "path", serviceFile,
                "kind", "source",
                "language", "Java",
                "module", "order",
                "roles", List.of("service")
        )), List.of());
        long sourceId = indexMarkdownSource("deep scan budget source");
        llmClient.reset();

        JsonNode askJson = ask(sourceId, "How is the order service implemented?");

        assertThat(askJson.path("evidenceEvaluation").path("answerGuardDecision").asText()).isEqualTo("supported");
        assertThat(llmClient.callCount()).isEqualTo(1);

        JsonNode events = runEvents(askJson.path("runId").asLong());
        assertThat(events)
                .extracting(event -> event.path("eventType").asText())
                .contains("KNOWLEDGE_SOURCE_EVIDENCE_LOOP_SUPPORTED")
                .doesNotContain("KNOWLEDGE_DEEP_SCAN_STARTED", "KNOWLEDGE_DEEP_SCAN_FINISHED");
        assertThat(askJson.path("citations"))
                .anySatisfy(citation -> {
                    assertThat(citation.path("filePath").asText()).isEqualTo(serviceFile);
                    assertThat(citation.path("scoreReasons"))
                            .anySatisfy(reason -> assertThat(reason.asText()).isEqualTo("source_evidence_loop"));
                });
    }

    private void writeGeneratedImplementationSummary() throws Exception {
        Path generatedDir = Files.createDirectories(projectRoot.resolve(".ai/generated"));
        Files.writeString(generatedDir.resolve("implementation-summary.md"), """
                # Generated Implementation Summary

                Generated notes say OrderService is responsible for order implementation details.
                """);
    }

    private void writeCodeMap(List<Map<String, Object>> files, List<Map<String, Object>> symbols) throws Exception {
        Path aiDir = Files.createDirectories(projectRoot.resolve(".ai"));
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(aiDir.resolve("code-map.json").toFile(), Map.of(
                        "schemaVersion", "2",
                        "projectName", "controlled-deep-scan-demo",
                        "files", files,
                        "symbols", symbols
                ));
    }

    private void writeProjectFile(String relativePath, String content) throws Exception {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String largeServiceSource() {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                package com.acme.order;

                import org.springframework.stereotype.Service;

                @Service
                public class BudgetedOrderService {
                    public String createOrder(String sku) {
                        return sku;
                    }
                """);
        for (int i = 0; i < 420; i++) {
            builder.append("    public String route").append(i).append("() { return \"order-")
                    .append(i)
                    .append("\"; }\n");
        }
        builder.append("}\n");
        return builder.toString();
    }

    private long indexMarkdownSource(String name) {
        KnowledgeSource source = indexService.createSource(new CreateKnowledgeSourceCommand(
                name,
                projectRoot.toString(),
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

    private JsonNode runEvents(long runId) throws Exception {
        String response = mockMvc.perform(get("/api/knowledge/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("events");
    }

    private String eventOutput(JsonNode events, String eventType) {
        for (JsonNode event : events) {
            if (eventType.equals(event.path("eventType").asText())) {
                return event.path("outputSummary").asText();
            }
        }
        return "";
    }

    @TestConfiguration
    static class TestLlmConfig {

        @Bean
        StubDeepScanLlmClient stubDeepScanLlmClient() {
            return new StubDeepScanLlmClient();
        }
    }

    static class StubDeepScanLlmClient implements LlmClient {

        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public LlmResponse chat(LlmRequest request) {
            callCount.incrementAndGet();
            return new LlmResponse(
                    "The implementation is grounded in the selected source evidence pack. [S1]",
                    request.modelName(),
                    160,
                    28
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
