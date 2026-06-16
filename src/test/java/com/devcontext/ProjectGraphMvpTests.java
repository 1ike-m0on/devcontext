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
import com.devcontext.domain.codemap.CodeMapConfigKey;
import com.devcontext.domain.codemap.CodeMapDependencyEdge;
import com.devcontext.domain.codemap.CodeMapFileEntry;
import com.devcontext.domain.codemap.CodeMapRoutingHint;
import com.devcontext.domain.codemap.CodeMapTestRelation;
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
    void routesCodeMapV2SectionsThroughSummaryAndNeighbors() throws Exception {
        createGraphRoutingFixture();
        long projectId = createProject("graph-routing-demo");

        ProjectGraphSummary summary = graphService.rebuildGraph(projectId);

        assertThat(summary.nodeTypeCounts())
                .containsKeys("endpoint", "config_key", "sql_hint", "mapper_hint", "entity_hint", "file", "file_role", "symbol");
        assertThat(summary.edgeTypeCounts())
                .containsKeys("configured_in", "hinted_by", "tests", "type_dependency", "has_role", "handled_by", "defined_in");

        JsonNode apiSummary = readGraphSummary(projectId);
        assertThat(apiSummary.path("nodeTypeCounts").path("config_key").asInt())
                .isEqualTo(summary.nodeTypeCounts().get("config_key"));
        assertThat(apiSummary.path("edgeTypeCounts").path("tests").asInt())
                .isEqualTo(summary.edgeTypeCounts().get("tests"));

        JsonNode endpointNeighbors = readNeighbors(projectId, "endpoint:GET /api/orders", null);
        assertThat(endpointNeighbors.path("edges"))
                .extracting(edge -> edge.path("edgeType").asText())
                .contains("handled_by", "defined_in");

        JsonNode configNeighbors = readNeighbors(
                projectId,
                "config_key:src/main/resources/application.yml#spring.datasource.url",
                null
        );
        assertThat(configNeighbors.path("seedNodes"))
                .anySatisfy(node -> {
                    assertThat(node.path("nodeType").asText()).isEqualTo("config_key");
                    assertThat(node.path("evidenceType").asText()).isEqualTo("CONFIG");
                });
        assertThat(configNeighbors.path("neighborNodes"))
                .anySatisfy(node -> assertThat(node.path("stableKey").asText())
                        .isEqualTo("file:src/main/resources/application.yml"));
        assertThat(configNeighbors.path("edges"))
                .anySatisfy(edge -> {
                    assertThat(edge.path("edgeType").asText()).isEqualTo("configured_in");
                    assertThat(edge.path("label").asText()).contains("Code Map config key");
                });

        JsonNode sqlNeighbors = readNeighbors(
                projectId,
                "sql_hint:src/main/resources/db/migration/V1__orders.sql#sql_table:orders",
                null
        );
        assertThat(sqlNeighbors.path("seedNodes"))
                .anySatisfy(node -> assertThat(node.path("nodeType").asText()).isEqualTo("sql_hint"));
        assertThat(sqlNeighbors.path("edges"))
                .anySatisfy(edge -> {
                    assertThat(edge.path("edgeType").asText()).isEqualTo("hinted_by");
                    assertThat(edge.path("label").asText()).contains("Code Map SQL hint");
                });

        JsonNode entityNeighbors = readNeighbors(
                projectId,
                "entity_hint:src/main/java/com/acme/order/OrderEntity.java#entity_table:orders",
                null
        );
        assertThat(entityNeighbors.path("seedNodes"))
                .anySatisfy(node -> assertThat(node.path("nodeType").asText()).isEqualTo("entity_hint"));
        assertThat(entityNeighbors.path("edges"))
                .anySatisfy(edge -> assertThat(edge.path("label").asText()).contains("Code Map entity hint"));

        JsonNode testNeighbors = readNeighbors(
                projectId,
                "file:src/test/java/com/acme/order/OrderServiceTest.java",
                null
        );
        assertThat(testNeighbors.path("neighborNodes"))
                .anySatisfy(node -> assertThat(node.path("stableKey").asText())
                        .isEqualTo("file:src/main/java/com/acme/order/OrderService.java"));
        assertThat(testNeighbors.path("edges"))
                .anySatisfy(edge -> {
                    assertThat(edge.path("edgeType").asText()).isEqualTo("tests");
                    assertThat(edge.path("label").asText()).contains("name_convention");
                });

        JsonNode dependencyNeighbors = readNeighbors(
                projectId,
                "symbol:src/main/java/com/acme/order/OrderController.java#OrderController",
                null
        );
        assertThat(dependencyNeighbors.path("neighborNodes"))
                .anySatisfy(node -> assertThat(node.path("stableKey").asText())
                        .isEqualTo("symbol:src/main/java/com/acme/order/OrderService.java#OrderService"));
        assertThat(dependencyNeighbors.path("edges"))
                .anySatisfy(edge -> {
                    assertThat(edge.path("edgeType").asText()).isEqualTo("type_dependency");
                    assertThat(edge.path("label").asText()).contains("Code Map dependency edge");
                });
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

    private void createGraphRoutingFixture() throws Exception {
        writeProjectFile("pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>graph-routing-demo</artifactId>
                </project>
                """);
        writeProjectFile("AGENTS.md", """
                # Project Guide

                Graph routing demo handles order reads.
                """);
        writeProjectFile("src/main/resources/application.yml", """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost/orders
                """);
        writeProjectFile("src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY
                );
                """);
        writeProjectFile("src/main/resources/mappers/OrderMapper.xml", """
                <mapper namespace="com.acme.order.OrderMapper">
                    <select id="findByStatus" resultType="com.acme.order.OrderEntity">
                        SELECT * FROM orders WHERE status = #{status}
                    </select>
                </mapper>
                """);
        writeProjectFile("src/main/java/com/acme/order/OrderController.java", """
                package com.acme.order;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class OrderController {
                    private final OrderService orderService;

                    public OrderController(OrderService orderService) {
                        this.orderService = orderService;
                    }

                    @GetMapping("/api/orders")
                    public void list() {
                        orderService.listOrders();
                    }
                }
                """);
        writeProjectFile("src/main/java/com/acme/order/OrderService.java", """
                package com.acme.order;

                import org.springframework.stereotype.Service;

                @Service
                public class OrderService {
                    public void listOrders() {
                    }
                }
                """);
        writeProjectFile("src/main/java/com/acme/order/OrderRepository.java", """
                package com.acme.order;

                import org.springframework.stereotype.Repository;

                @Repository
                public class OrderRepository {
                    public void findByStatus() {
                    }
                }
                """);
        writeProjectFile("src/main/java/com/acme/order/OrderEntity.java", """
                package com.acme.order;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;

                @Entity
                @Table(name = "orders")
                public class OrderEntity {
                    private Long id;
                }
                """);
        writeProjectFile("src/test/java/com/acme/order/OrderServiceTest.java", """
                package com.acme.order;

                class OrderServiceTest {
                    void listOrders() {
                    }
                }
                """);
        Path aiDir = Files.createDirectories(projectRoot.resolve(".ai"));
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(aiDir.resolve("code-map.json").toFile(), routingCodeMap());
    }

    private void writeProjectFile(String relativePath, String content) throws Exception {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
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

    private CodeMap routingCodeMap() {
        String controllerFile = "src/main/java/com/acme/order/OrderController.java";
        String serviceFile = "src/main/java/com/acme/order/OrderService.java";
        String repositoryFile = "src/main/java/com/acme/order/OrderRepository.java";
        String entityFile = "src/main/java/com/acme/order/OrderEntity.java";
        String configFile = "src/main/resources/application.yml";
        String sqlFile = "src/main/resources/db/migration/V1__orders.sql";
        String mapperFile = "src/main/resources/mappers/OrderMapper.xml";
        String testFile = "src/test/java/com/acme/order/OrderServiceTest.java";
        return new CodeMap(
                CodeMap.CURRENT_SCHEMA_VERSION,
                Instant.now().toString(),
                "graph-routing-demo",
                projectRoot.toString(),
                "Java",
                "Spring Boot",
                "Maven",
                "main",
                "abc123",
                List.of(new CodeModule(
                        "order",
                        "src/main/java/com/acme/order",
                        List.of("OrderController", "OrderService", "OrderRepository", "OrderEntity"),
                        "HTTP/API and data access routing"
                )),
                List.of(new CodeEntrypoint(
                        "controller",
                        controllerFile,
                        List.of("list")
                )),
                List.of(
                        new CodeSymbol(
                                "OrderController",
                                "controller",
                                "order",
                                controllerFile,
                                List.of("list"),
                                List.of("GET /api/orders -> list"),
                                List.of("OrderService"),
                                List.of("Spring MVC"),
                                List.of("order")
                        ),
                        new CodeSymbol(
                                "OrderService",
                                "service",
                                "order",
                                serviceFile,
                                List.of("listOrders"),
                                List.of(),
                                List.of("OrderRepository"),
                                List.of("Spring"),
                                List.of("order")
                        ),
                        new CodeSymbol(
                                "OrderRepository",
                                "repository",
                                "order",
                                repositoryFile,
                                List.of("findByStatus"),
                                List.of(),
                                List.of("OrderEntity"),
                                List.of("Spring Data"),
                                List.of("order")
                        ),
                        new CodeSymbol(
                                "OrderEntity",
                                "entity",
                                "order",
                                entityFile,
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of("JPA"),
                                List.of("order")
                        )
                ),
                List.of(new CodeEndpoint(
                        "GET",
                        "/api/orders",
                        "list",
                        "OrderController",
                        "order",
                        controllerFile,
                        List.of("order")
                )),
                List.of(new CodeDependency(
                        "OrderController",
                        controllerFile,
                        "OrderService",
                        "order"
                )),
                List.<CodeTechnologySignal>of(),
                List.<CodeRuntimeComponent>of(),
                List.<CodeDomainTerm>of(),
                Map.of("test", "mvn test"),
                List.of(configFile),
                List.of("src/test/java"),
                List.of("AGENTS.md"),
                List.of(),
                List.of(
                        new CodeMapFileEntry(
                                controllerFile,
                                "source",
                                "Java",
                                "order",
                                List.of("controller", "endpoint", "spring-component")
                        ),
                        new CodeMapFileEntry(
                                serviceFile,
                                "source",
                                "Java",
                                "order",
                                List.of("service", "spring-component")
                        ),
                        new CodeMapFileEntry(
                                repositoryFile,
                                "source",
                                "Java",
                                "order",
                                List.of("repository", "data-access", "spring-component")
                        ),
                        new CodeMapFileEntry(
                                entityFile,
                                "source",
                                "Java",
                                "order",
                                List.of("entity", "domain-entity")
                        ),
                        new CodeMapFileEntry(
                                configFile,
                                "configuration",
                                "YAML",
                                "resources",
                                List.of("configuration")
                        ),
                        new CodeMapFileEntry(
                                sqlFile,
                                "database_schema",
                                "SQL",
                                "migration",
                                List.of("sql", "database-schema")
                        ),
                        new CodeMapFileEntry(
                                mapperFile,
                                "mapper",
                                "XML",
                                "mappers",
                                List.of("mapper", "data-access")
                        ),
                        new CodeMapFileEntry(
                                testFile,
                                "test",
                                "Java",
                                "order",
                                List.of("test")
                        )
                ),
                List.of(new CodeMapConfigKey(
                        "spring.datasource.url",
                        configFile,
                        "resources"
                )),
                List.of(
                        new CodeMapRoutingHint("sql_table", "orders", sqlFile, "migration"),
                        new CodeMapRoutingHint("sql_table", "orders", mapperFile, "OrderMapper")
                ),
                List.of(new CodeMapRoutingHint("mapper_xml", "OrderMapper", mapperFile, "com.acme.order.OrderMapper")),
                List.of(
                        new CodeMapRoutingHint("entity", "OrderEntity", entityFile, "OrderEntity"),
                        new CodeMapRoutingHint("entity_table", "orders", entityFile, "OrderEntity")
                ),
                List.of(new CodeMapTestRelation(
                        testFile,
                        serviceFile,
                        "name_convention"
                )),
                List.of(new CodeMapDependencyEdge(
                        controllerFile,
                        "OrderController",
                        null,
                        "OrderService",
                        "type_dependency"
                ))
        );
    }
}
