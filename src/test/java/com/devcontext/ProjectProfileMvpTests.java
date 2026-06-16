package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.devcontext.domain.profile.ProjectProfile;
import com.devcontext.domain.profile.ProjectProfileFact;
import com.devcontext.ports.context.ContextDocumentRepository;
import com.devcontext.ports.profile.ProjectProfileRepository;
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
        "spring.datasource.url=jdbc:sqlite:target/devcontext-project-profile-test.sqlite",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
class ProjectProfileMvpTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ContextDocumentRepository contextDocumentRepository;

    @Autowired
    private KnowledgeIndexApplicationService knowledgeIndexService;

    @Autowired
    private ProjectProfileRepository profileRepository;

    @TempDir
    private Path projectRoot;

    @Test
    void buildsPersistsAndReadsProjectProfileWithSourceReferences() throws Exception {
        createProfileFixture();
        long projectId = createProject("profile-demo");
        Instant now = Instant.now();
        contextDocumentRepository.upsert(new ContextDocument(
                null, projectId, "AGENTS", "AGENTS.md", true, "written", "abc123", now, now));
        contextDocumentRepository.upsert(new ContextDocument(
                null, projectId, "CODE_MAP", ".ai/code-map.json", true, "written", "abc123", now, now));
        contextDocumentRepository.upsert(new ContextDocument(
                null, projectId, "BUSINESS_CONTEXT", ".ai/manual/business-context.md", false, "written", null, now, now));

        var source = knowledgeIndexService.createSource(new CreateKnowledgeSourceCommand(
                "profile knowledge",
                projectRoot.toString(),
                "project_ai_docs"
        ));
        knowledgeIndexService.indexSource(source.id());

        JsonNode profile = readProfile(projectId);

        assertThat(profile.path("id").asLong()).isPositive();
        assertThat(profile.path("projectId").asLong()).isEqualTo(projectId);
        assertThat(profile.path("status").asText()).isEqualTo("ready");
        assertFreshness(profile, "no_profile");
        assertThat(profile.path("freshness").path("lastBuiltAt").asText()).isEqualTo(profile.path("generatedAt").asText());
        assertThat(profile.path("freshness").path("sourceCount").asInt()).isPositive();
        assertThat(profile.path("freshness").path("staleReasons"))
                .extracting(JsonNode::asText)
                .contains("no_profile");
        assertThat(profile.path("facts")).isNotEmpty();
        assertThat(profile.path("facts"))
                .anySatisfy(fact -> {
                    assertThat(fact.path("factType").asText()).isEqualTo("tech_stack");
                    assertThat(fact.path("name").asText()).isEqualTo("Spring MVC");
                    JsonNode sourceRef = fact.path("sourceReferences").get(0);
                    assertThat(sourceRef.path("sourcePath").asText()).isEqualTo("src/main/java/com/acme/order/OrderController.java");
                    assertThat(sourceRef.path("evidenceType").asText()).isEqualTo("SERVICE_CODE");
                    assertThat(sourceRef.path("sourceKind").asText()).isEqualTo("source_code");
                    assertThat(sourceRef.path("sourceReliability").asText()).isEqualTo("primary");
                });
        assertThat(profile.path("facts"))
                .anySatisfy(fact -> {
                    assertThat(fact.path("factType").asText()).isEqualTo("module");
                    assertThat(fact.path("name").asText()).isEqualTo("order");
                    JsonNode sourceRef = fact.path("sourceReferences").get(0);
                    assertThat(sourceRef.path("sourcePath").asText()).isEqualTo(".ai/code-map.json");
                    assertThat(sourceRef.path("evidenceType").asText()).isEqualTo("CODE_MAP");
                    assertThat(sourceRef.path("sourceKind").asText()).isEqualTo("code_structure");
                });
        assertThat(profile.path("facts"))
                .anySatisfy(fact -> {
                    assertThat(fact.path("factType").asText()).isEqualTo("context_asset");
                    assertThat(fact.path("name").asText()).isEqualTo("BUSINESS_CONTEXT");
                    JsonNode sourceRef = fact.path("sourceReferences").get(0);
                    assertThat(sourceRef.path("evidenceType").asText()).isEqualTo("MANUAL_DOC");
                    assertThat(sourceRef.path("sourceReliability").asText()).isEqualTo("secondary");
                });
        assertThat(profile.path("facts"))
                .anySatisfy(fact -> {
                    assertThat(fact.path("factType").asText()).isEqualTo("evidence_coverage");
                    assertThat(fact.path("name").asText()).isEqualTo("SQL_SCHEMA");
                    JsonNode sourceRef = fact.path("sourceReferences").get(0);
                    assertThat(sourceRef.path("evidenceType").asText()).isEqualTo("SQL_SCHEMA");
                    assertThat(sourceRef.path("sourceKind").asText()).isEqualTo("data_schema");
                    assertThat(sourceRef.path("sourceReliability").asText()).isEqualTo("primary");
                });
        assertThat(profile.path("facts"))
                .anySatisfy(fact -> {
                    assertThat(fact.path("factType").asText()).isEqualTo("endpoint");
                    assertThat(fact.path("name").asText()).isEqualTo("POST /api/orders");
                    JsonNode sourceRef = fact.path("sourceReferences").get(0);
                    assertThat(sourceRef.path("evidenceType").asText()).isEqualTo("API_CONTROLLER");
                    assertThat(sourceRef.path("sourceKind").asText()).isEqualTo("api_surface");
                });

        ProjectProfile persisted = profileRepository.findByProjectId(projectId).orElseThrow();
        assertThat(persisted.id()).isEqualTo(profile.path("id").asLong());
        assertThat(persisted.facts()).isNotEmpty();

        JsonNode refreshedProfile = readProfile(projectId);
        assertFreshness(refreshedProfile, "current");
        assertThat(refreshedProfile.path("freshness").path("lastBuiltAt").asText())
                .isEqualTo(profile.path("generatedAt").asText());
        assertThat(refreshedProfile.path("freshness").path("staleReasons")).isEmpty();
    }

    @Test
    void profileApiDegradesClearlyWhenCodeMapAndContextAssetsAreMissing() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        long projectId = createProject("degraded-profile-demo");

        JsonNode profile = readProfile(projectId);

        assertThat(profile.path("status").asText()).isEqualTo("degraded");
        assertFreshness(profile, "degraded");
        assertThat(profile.path("freshness").path("warnings"))
                .extracting(JsonNode::asText)
                .anySatisfy(warning -> assertThat(warning).contains("Missing .ai/code-map.json"));
        assertThat(profile.path("facts"))
                .anySatisfy(fact -> {
                    assertThat(fact.path("factType").asText()).isEqualTo("tech_stack");
                    assertThat(fact.path("name").asText()).isEqualTo("registered_language");
                    assertThat(fact.path("sourceReferences").get(0).path("sourcePath").asText())
                            .isEqualTo(projectRoot.toAbsolutePath().normalize().toString());
                });
        assertThat(profile.path("warnings"))
                .anySatisfy(warning -> assertThat(warning.asText()).contains("Missing .ai/code-map.json"));
        assertThat(profile.path("warnings"))
                .anySatisfy(warning -> assertThat(warning.asText()).contains("No context document records"));
        assertThat(profile.path("warnings"))
                .anySatisfy(warning -> assertThat(warning.asText()).contains("No indexed knowledge source"));
    }

    @Test
    void profileFreshnessReportsMissingSourceReferences() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project></project>");
        long projectId = createProject("profile-no-source-demo");
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        profileRepository.upsertByProjectId(new ProjectProfile(
                null,
                projectId,
                "ready",
                "seeded profile without source references",
                List.of(new ProjectProfileFact("module", "orders", "order module", List.of())),
                List.of(),
                old,
                old,
                old
        ));

        JsonNode profile = readProfile(projectId);

        assertFreshness(profile, "no_source_references");
        assertThat(profile.path("freshness").path("lastBuiltAt").asText()).isEqualTo(old.toString());
        assertThat(profile.path("freshness").path("sourceCount").asInt()).isZero();
        assertThat(profile.path("freshness").path("staleReasons"))
                .extracting(JsonNode::asText)
                .contains("no_source_references");
    }

    @Test
    void profileFreshnessReportsStaleSourceRecords() throws Exception {
        createProfileFixture();
        long projectId = createProject("profile-stale-source-demo");
        Instant now = Instant.now();
        contextDocumentRepository.upsert(new ContextDocument(
                null, projectId, "AGENTS", "AGENTS.md", true, "written", "abc123", now, now));
        contextDocumentRepository.upsert(new ContextDocument(
                null, projectId, "CODE_MAP", ".ai/code-map.json", true, "written", "abc123", now, now));
        contextDocumentRepository.upsert(new ContextDocument(
                null, projectId, "BUSINESS_CONTEXT", ".ai/manual/business-context.md", false, "written", null, now, now));
        var source = knowledgeIndexService.createSource(new CreateKnowledgeSourceCommand(
                "profile stale source knowledge",
                projectRoot.toString(),
                "project_ai_docs"
        ));
        knowledgeIndexService.indexSource(source.id());

        JsonNode profile = readProfile(projectId);
        Instant staleUpdatedAt = Instant.parse(profile.path("generatedAt").asText()).plusSeconds(60);
        contextDocumentRepository.upsert(new ContextDocument(
                null,
                projectId,
                "BUSINESS_CONTEXT",
                ".ai/manual/business-context.md",
                false,
                "written",
                null,
                now,
                staleUpdatedAt
        ));

        JsonNode staleProfile = readProfile(projectId);

        assertFreshness(staleProfile, "stale_source");
        assertThat(staleProfile.path("freshness").path("lastBuiltAt").asText())
                .isEqualTo(profile.path("generatedAt").asText());
        assertThat(staleProfile.path("freshness").path("sourceCount").asInt()).isPositive();
        assertThat(staleProfile.path("freshness").path("staleReasons"))
                .extracting(JsonNode::asText)
                .contains("context_document_newer");
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

    private JsonNode readProfile(long projectId) throws Exception {
        String response = mockMvc.perform(get("/api/projects/{projectId}/profile", projectId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private void assertFreshness(JsonNode profile, String status) {
        assertThat(profile.path("freshness").path("freshnessStatus").asText()).isEqualTo(status);
    }

    private void createProfileFixture() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>profile-demo</artifactId>
                </project>
                """);
        Files.writeString(projectRoot.resolve("AGENTS.md"), """
                # Project Guide

                Profile demo handles order creation.
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
                "profile-demo",
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
                List.<CodeSymbol>of(),
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
