package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.application.codemap.CodeMapGenerator;
import com.devcontext.domain.codemap.CodeMap;
import com.devcontext.domain.project.Project;
import com.devcontext.domain.project.ProjectScan;
import com.devcontext.domain.project.ScannedJavaFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodeMapV2SchemaTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CodeMapGenerator generator = new CodeMapGenerator();

    @Test
    void generatorWritesV2SchemaAndRoutingSections() throws Exception {
        CodeMap codeMap = generator.generate(project(), scan());
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(codeMap);
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.path("schemaVersion").asText()).isEqualTo("2");
        assertThat(root.path("files"))
                .anySatisfy(file -> {
                    assertThat(file.path("path").asText())
                            .isEqualTo("src/main/java/com/acme/order/OrderController.java");
                    assertThat(file.path("kind").asText()).isEqualTo("source");
                    assertThat(file.path("module").asText()).isEqualTo("order");
                    assertThat(file.path("roles"))
                            .anySatisfy(role -> assertThat(role.asText()).isEqualTo("controller"));
                })
                .anySatisfy(file -> {
                    assertThat(file.path("path").asText()).isEqualTo("src/main/resources/application.yml");
                    assertThat(file.path("kind").asText()).isEqualTo("configuration");
                });
        assertThat(root.path("symbols"))
                .anySatisfy(symbol -> assertThat(symbol.path("name").asText()).isEqualTo("OrderController"))
                .anySatisfy(symbol -> assertThat(symbol.path("name").asText()).isEqualTo("OrderMapper"));
        assertThat(root.path("endpoints"))
                .anySatisfy(endpoint -> {
                    assertThat(endpoint.path("httpMethod").asText()).isEqualTo("GET");
                    assertThat(endpoint.path("path").asText()).isEqualTo("/api/orders");
                });
        assertThat(root.path("configKeys")).isEmpty();
        assertThat(root.path("sqlHints")).isEmpty();
        assertThat(root.path("mapperHints"))
                .anySatisfy(hint -> {
                    assertThat(hint.path("kind").asText()).isEqualTo("mapper");
                    assertThat(hint.path("name").asText()).isEqualTo("OrderMapper");
                });
        assertThat(root.path("entityHints"))
                .anySatisfy(hint -> {
                    assertThat(hint.path("kind").asText()).isEqualTo("entity");
                    assertThat(hint.path("name").asText()).isEqualTo("OrderEntity");
                });
        assertThat(root.path("testRelations")).isEmpty();
        assertThat(root.path("dependencyEdges"))
                .anySatisfy(edge -> {
                    assertThat(edge.path("fromSymbol").asText()).isEqualTo("OrderController");
                    assertThat(edge.path("toSymbol").asText()).isEqualTo("OrderService");
                    assertThat(edge.path("edgeType").asText()).isEqualTo("type_dependency");
                });

        CodeMap parsed = objectMapper.readValue(json, CodeMap.class);
        assertThat(parsed.isV2()).isTrue();
        assertThat(parsed.files()).extracting("path")
                .contains("src/main/java/com/acme/order/OrderController.java");
        assertThat(parsed.configKeys()).isEmpty();
        assertThat(parsed.mapperHints()).extracting("name").contains("OrderMapper");
        assertThat(parsed.entityHints()).extracting("name").contains("OrderEntity");
        assertThat(parsed.dependencyEdges()).extracting("toSymbol").contains("OrderService");
    }

    @Test
    void legacyCodeMapWithoutSchemaVersionStillParsesOldFields() throws Exception {
        String legacyJson = """
                {
                  "generatedAt": "2026-06-16T00:00:00Z",
                  "projectName": "legacy-order-service",
                  "rootPath": "D:/workspace/legacy-order-service",
                  "language": "Java",
                  "framework": "Spring Boot",
                  "buildTool": "Maven",
                  "gitBranch": "main",
                  "gitCommit": "abc123",
                  "modules": [
                    {
                      "name": "order",
                      "path": "src/main/java/com/acme/order",
                      "classes": ["OrderController"],
                      "responsibility": "HTTP/API entrypoints"
                    }
                  ],
                  "entrypoints": [],
                  "symbols": [],
                  "endpoints": [
                    {
                      "httpMethod": "POST",
                      "path": "/api/orders",
                      "handlerMethod": "create",
                      "className": "OrderController",
                      "module": "order",
                      "file": "src/main/java/com/acme/order/OrderController.java",
                      "domainTerms": ["order"]
                    }
                  ],
                  "dependencies": [],
                  "technologies": [],
                  "runtimeComponents": [],
                  "domainTerms": [],
                  "commands": {"test": "mvn test"},
                  "configs": [],
                  "testRoots": [],
                  "docs": [],
                  "todos": []
                }
                """;

        CodeMap codeMap = objectMapper.readValue(legacyJson, CodeMap.class);

        assertThat(codeMap.schemaVersion()).isEqualTo("1");
        assertThat(codeMap.isV2()).isFalse();
        assertThat(codeMap.modules()).extracting("name").contains("order");
        assertThat(codeMap.endpoints()).extracting("path").contains("/api/orders");
        assertThat(codeMap.commands()).containsEntry("test", "mvn test");
        assertThat(codeMap.files()).isEmpty();
        assertThat(codeMap.configKeys()).isEmpty();
        assertThat(codeMap.sqlHints()).isEmpty();
        assertThat(codeMap.mapperHints()).isEmpty();
        assertThat(codeMap.entityHints()).isEmpty();
        assertThat(codeMap.testRelations()).isEmpty();
        assertThat(codeMap.dependencyEdges()).isEmpty();
    }

    private Project project() {
        Instant now = Instant.parse("2026-06-16T00:00:00Z");
        return new Project(
                1L,
                "order-service",
                "D:/workspace/order-service",
                "Java",
                "Spring Boot",
                "main",
                now,
                now
        );
    }

    private ProjectScan scan() {
        return new ProjectScan(
                "D:/workspace/order-service",
                "Java",
                "Spring Boot",
                "Maven",
                true,
                "main",
                "abc123",
                List.of("src/main/java", "src/main/resources", "src/test/java"),
                List.of("src/main/java"),
                List.of("src/main/resources"),
                List.of("src/test/java"),
                List.of("pom.xml", "src/main/resources/application.yml"),
                List.of("README.md", "docs/design/order-flow.md"),
                List.of(
                        new ScannedJavaFile(
                                "src/main/java/com/acme/order/OrderController.java",
                                "com.acme.order",
                                "OrderController",
                                List.of("@RestController"),
                                List.of("list"),
                                List.of("GET /api/orders -> list"),
                                List.of("OrderService"),
                                List.of("Spring MVC")
                        ),
                        new ScannedJavaFile(
                                "src/main/java/com/acme/order/OrderService.java",
                                "com.acme.order",
                                "OrderService",
                                List.of("@Service"),
                                List.of("listOrders"),
                                List.of(),
                                List.of("OrderMapper"),
                                List.of()
                        ),
                        new ScannedJavaFile(
                                "src/main/java/com/acme/order/OrderMapper.java",
                                "com.acme.order",
                                "OrderMapper",
                                List.of(),
                                List.of("findOrders"),
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        new ScannedJavaFile(
                                "src/main/java/com/acme/order/OrderEntity.java",
                                "com.acme.order",
                                "OrderEntity",
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ),
                Map.of("test", "mvn test", "run", "mvn spring-boot:run"),
                List.of()
        );
    }
}
