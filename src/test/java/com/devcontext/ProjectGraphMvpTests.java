package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devcontext.application.graph.ProjectGraphApplicationService;
import com.devcontext.application.knowledge.CreateKnowledgeSourceCommand;
import com.devcontext.application.knowledge.KnowledgeIndexApplicationService;
import com.devcontext.domain.codemap.CodeDependency;
import com.devcontext.domain.codemap.CodeDomainTerm;
import com.devcontext.domain.codemap.CodeEndpoint;
import com.devcontext.domain.codemap.CodeEntrypoint;
import com.devcontext.domain.codemap.CodeMap;
import com.devcontext.domain.codemap.CodeModule;
import com.devcontext.domain.codemap.CodeRuntimeComponent;
import com.devcontext.domain.codemap.CodeSymbol;
import com.devcontext.domain.codemap.CodeTechnologySignal;
import com.devcontext.domain.context.ContextDocument;
import com.devcontext.domain.graph.ProjectGraphNeighborhood;
import com.devcontext.domain.graph.ProjectGraphSummary;
import com.devcontext.ports.context.ContextDocumentRepository;
import com.devcontext.ports.graph.ProjectGraphRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-project-graph-test.sqlite",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
class ProjectGraphMvpTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ContextDocumentRepository contextDocumentRepository;

    @Autowired
    private KnowledgeIndexApplicationService knowledgeIndexService;

    @Autowired
    private ProjectGraphApplicationService graphService;

    @Autowired
    private ProjectGraphRepository graphRepository;

    @TempDir
    private Path projectRoot;

    @Test
    void buildsGraphIdempotentlyAndQueriesEndpointNeighbors() throws Exception {
        createGraphFixture();
        long projectId = createProject("graph-demo");
        recordContextDocuments(projectId);
        var source = knowledgeIndexService.createSource(new CreateKnowledgeSourceCommand(
                "graph knowledge",
                projectRoot.toString(),
                "project_ai_docs"
        ));
        knowledgeIndexService.indexSource(source.id());

        ProjectGraphSummary firstBuild = graphService.rebuildGraph(projectId);
        ProjectGraphSummary secondBuild = graphService.rebuildGraph(projectId);

        assertThat(secondBuild.status()).isEqualTo("ready");
        assertThat(secondBuild.nodeCount()).isEqualTo(firstBuild.nodeCount());
        assertThat(secondBuild.edgeCount()).isEqualTo(firstBuild.edgeCount());
        assertThat(secondBuild.nodeTypeCounts())
                .containsKeys("endpoint", "file", "module", "profile_fact", "symbol");
        assertThat(secondBuild.edgeTypeCounts())
                .containsKeys("belongs_to", "defined_in", "handled_by", "supported_by");
        assertThat(graphRepository.findNodesByProjectId(projectId))
                .extracting("stableKey")
                .doesNotHaveDuplicates();
        assertThat(graphRepository.findEdgesByProjectId(projectId))
                .extracting("stableKey")
                .doesNotHaveDuplicates();

        JsonNode summary = readGraphSummary(projectId);
        assertThat(summary.path("nodeCount").asInt()).isEqualTo(secondBuild.nodeCount());
        assertThat(summary.path("edgeCount").asInt()).isEqualTo(secondBuild.edgeCount());

        JsonNode endpointNeighbors = readNeighbors(projectId, "endpoint:POST /api/orders", null);
        assertThat(endpointNeighbors.path("seedNodes"))
                .anySatisfy(node -> assertThat(node.path("nodeType").asText()).isEqualTo("endpoint"));
        assertThat(endpointNeighbors.path("neighborNodes"))
                .anySatisfy(node -> {
                    assertThat(node.path("nodeType").asText()).isEqualTo("symbol");
                    assertThat(node.path("label").asText()).isEqualTo("OrderController");
                });
        assertThat(endpointNeighbors.path("neighborNodes"))
                .anySatisfy(node -> {
                    assertThat(node.path("nodeType").asText()).isEqualTo("module");
                    assertThat(node.path("label").asText()).isEqualTo("order");
                });
        assertThat(endpointNeighbors.path("edges"))
                .extracting(edge -> edge.path("edgeType").asText())
                .contains("handled_by", "defined_in", "belongs_to");

        ProjectGraphNeighborhood fileNeighborhood = graphService.neighbors(
                projectId,
                "file:src/main/java/com/acme/order/OrderController.java",
                null,
                50
        );
        assertThat(fileNeighborhood.neighborNodes())
                .anySatisfy(node -> assertThat(node.nodeType()).isEqualTo("profile_fact"));
        assertThat(fileNeighborhood.neighborNodes())
                .anySatisfy(node -> assertThat(node.nodeType()).isEqualTo("symbol"));
    }

    @Test
    void graphBuildDegradesWhenCodeMapIsMissingWithoutFailing() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        long projectId = createProject("missing-code-map-graph-demo");

        ProjectGraphSummary summary = graphService.rebuildGraph(projectId);

        assertThat(summary.status()).isEqualTo("degraded");
        assertThat(summary.nodeCount()).isPositive();
        assertThat(summary.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("Missing .ai/code-map.json"));
        assertThat(readGraphSummary(projectId).path("nodeCount").asInt()).isEqualTo(summary.nodeCount());
    }

    private long createProject(String name) throws Exception {
        String response = mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "rootPath", projectRoot.toString(),
                                "defaultBranch", "main"
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private JsonNode readGraphSummary(long projectId) throws Exception {
        String response = mockMvc.perform(get("/api/projects/{projectId}/graph", projectId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode readNeighbors(long projectId, String stableKey, String sourcePath) throws Exception {
        var request = get("/api/projects/{projectId}/graph/neighbors", projectId);
        if (stableKey != null) {
            request.param("stableKey", stableKey);
        }
        if (sourcePath != null) {
            request.param("sourcePath", sourcePath);
        }
        String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private void recordContextDocuments(long projectId) {
        Instant now = Instant.now();
        contextDocumentRepository.upsert(new ContextDocument(
                null, projectId, "AGENTS", "AGENTS.md", true, "written", "abc123", now, now));
        contextDocumentRepository.upsert(new ContextDocument(
                null, projectId, "CODE_MAP", ".ai/code-map.json", true, "written", "abc123", now, now));
        contextDocumentRepository.upsert(new ContextDocument(
                null, projectId, "BUSINESS_CONTEXT", ".ai/manual/business-context.md", false, "written", null, now, now));
    }

    private void createGraphFixture() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>graph-demo</artifactId>
                </project>
                """);
        Files.writeString(projectRoot.resolve("AGENTS.md"), """
                # Project Guide

                Graph demo handles order creation.
                """);
        Path aiDir = Files.createDirectories(projectRoot.resolve(".ai"));
        Path generatedDir = Files.createDirectories(aiDir.resolve("generated"));
        Path manualDir = Files.createDirectories(aiDir.resolve("manual"));
        Files.writeString(generatedDir.resolve("tech-architecture.md"), """
                # Tech Architecture

                - Spring MVC
                - SQLite
                """);
        Files.writeString(manualDir.resolve("business-context.md"), """
                # Business Context

                Operators create orders through the order API.
                """);
        Path controllerDir = Files.createDirectories(projectRoot.resolve("src/main/java/com/acme/order"));
        Files.writeString(controllerDir.resolve("OrderController.java"), """
                package com.acme.order;

                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class OrderController {
                    @PostMapping("/api/orders")
                    public void create() {
                    }
                }
                """);
        Path dbDir = Files.createDirectories(projectRoot.resolve("src/main/resources/db"));
        Files.writeString(dbDir.resolve("schema.sql"), """
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL
                );
                CREATE INDEX idx_orders_user_id ON orders(user_id);
                """);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(aiDir.resolve("code-map.json").toFile(), codeMap());
    }

    private CodeMap codeMap() {
        return new CodeMap(
                Instant.now().toString(),
                "graph-demo",
                projectRoot.toString(),
                "Java",
                "Spring Boot",
                "Maven",
                "main",
                "abc123",
                List.of(new CodeModule(
                        "order",
                        "src/main/java/com/acme/order",
                        List.of("OrderController"),
                        "HTTP/API entrypoints"
                )),
                List.of(new CodeEntrypoint(
                        "controller",
                        "src/main/java/com/acme/order/OrderController.java",
                        List.of("create")
                )),
                List.of(new CodeSymbol(
                        "OrderController",
                        "controller",
                        "order",
                        "src/main/java/com/acme/order/OrderController.java",
                        List.of("create"),
                        List.of("POST /api/orders -> create"),
                        List.of(),
                        List.of("Spring MVC"),
                        List.of("order")
                )),
                List.of(new CodeEndpoint(
                        "POST",
                        "/api/orders",
                        "create",
                        "OrderController",
                        "order",
                        "src/main/java/com/acme/order/OrderController.java",
                        List.of("order")
                )),
                List.<CodeDependency>of(),
                List.of(new CodeTechnologySignal(
                        "Spring MVC",
                        List.of("OrderController"),
                        List.of("src/main/java/com/acme/order/OrderController.java")
                )),
                List.<CodeRuntimeComponent>of(),
                List.<CodeDomainTerm>of(),
                Map.of("test", "mvn test"),
                List.of("src/main/resources/db/schema.sql"),
                List.of(),
                List.of("AGENTS.md"),
                List.of()
        );
    }
}
