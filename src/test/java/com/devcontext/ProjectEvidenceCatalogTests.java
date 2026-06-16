package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-evidence-catalog-test.sqlite",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
class ProjectEvidenceCatalogTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    private Path projectRoot;

    @Test
    void summarizesProjectEvidenceCoverageCounts() throws Exception {
        writeCompleteEvidenceFixture();
        long projectId = createProject("evidence-catalog-complete");

        JsonNode summary = readEvidenceCoverage(projectId);

        assertThat(summary.path("totalEvidenceCount").asInt()).isEqualTo(12);
        assertThat(summary.path("primaryEvidenceCount").asInt()).isEqualTo(7);
        assertThat(summary.path("secondaryEvidenceCount").asInt()).isEqualTo(3);
        assertThat(summary.path("derivedEvidenceCount").asInt()).isEqualTo(2);
        assertThat(summary.path("countsByEvidenceType").path("SERVICE_CODE").asInt()).isEqualTo(1);
        assertThat(summary.path("countsByEvidenceType").path("CONFIG").asInt()).isEqualTo(2);
        assertThat(summary.path("countsByEvidenceType").path("SQL_SCHEMA").asInt()).isEqualTo(1);
        assertThat(summary.path("countsByEvidenceType").path("TEST").asInt()).isEqualTo(1);
        assertThat(summary.path("countsByEvidenceType").path("MANUAL_DOC").asInt()).isEqualTo(3);
        assertThat(summary.path("countsByEvidenceType").path("GENERATED_DOC").asInt()).isEqualTo(1);
        assertThat(summary.path("countsByEvidenceType").path("CODE_MAP").asInt()).isEqualTo(1);
        assertThat(summary.path("countsByEvidenceType").path("BENCHMARK").asInt()).isEqualTo(2);

        assertThat(group(summary, "data_schema_mapper").path("count").asInt()).isEqualTo(1);
        assertThat(group(summary, "configuration").path("count").asInt()).isEqualTo(2);
        assertThat(group(summary, "test").path("count").asInt()).isEqualTo(1);
        assertThat(group(summary, "generated_documentation").path("primaryEvidence").asBoolean()).isFalse();
        assertThat(group(summary, "generated_documentation").path("sourceReliabilities"))
                .anySatisfy(value -> assertThat(value.asText()).isEqualTo("derived"));
        assertThat(summary.path("missingCategories"))
                .noneSatisfy(category -> assertThat(category.path("category").asText())
                        .isIn("data_schema_mapper", "configuration", "test"));
        assertThat(summary.path("skippedCategories"))
                .anySatisfy(category -> {
                    assertThat(category.path("category").asText()).isEqualTo("generated_documentation_as_primary_evidence");
                    assertThat(category.path("reason").asText()).contains("excluded from primary evidence");
                });
    }

    @Test
    void reportsMissingSqlConfigAndTestCoverage() throws Exception {
        Path sourceDir = Files.createDirectories(projectRoot.resolve("src/main/java/com/acme"));
        Files.writeString(sourceDir.resolve("OrderService.java"), """
                package com.acme;

                public class OrderService {
                    public void create() {
                    }
                }
                """);
        long projectId = createProject("evidence-catalog-missing-primary");

        JsonNode summary = readEvidenceCoverage(projectId);

        assertThat(group(summary, "source_code").path("count").asInt()).isEqualTo(1);
        assertThat(missingReason(summary, "configuration")).contains("No configuration");
        assertThat(missingReason(summary, "data_schema_mapper")).contains("No SQL schema");
        assertThat(missingReason(summary, "test")).contains("No test evidence");
    }

    @Test
    void doesNotTreatGeneratedDocsAsPrimaryEvidence() throws Exception {
        Path generatedDir = Files.createDirectories(projectRoot.resolve(".ai/generated"));
        Files.writeString(generatedDir.resolve("tech-architecture.md"), """
                # Tech Architecture

                Generated summary only.
                """);
        Files.writeString(projectRoot.resolve(".ai/code-map.json"), "{}");
        long projectId = createProject("evidence-catalog-generated-only");

        JsonNode summary = readEvidenceCoverage(projectId);

        JsonNode generatedGroup = group(summary, "generated_documentation");
        assertThat(generatedGroup.path("count").asInt()).isEqualTo(2);
        assertThat(generatedGroup.path("primaryEvidence").asBoolean()).isFalse();
        assertThat(summary.path("primaryEvidenceCount").asInt()).isZero();
        assertThat(summary.path("derivedEvidenceCount").asInt()).isEqualTo(2);
        assertThat(missingReason(summary, "source_code")).contains("No source code evidence");
        assertThat(summary.path("skippedCategories"))
                .anySatisfy(category -> assertThat(category.path("category").asText())
                        .isEqualTo("generated_documentation_as_primary_evidence"));
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

    private JsonNode readEvidenceCoverage(long projectId) throws Exception {
        String response = mockMvc.perform(get("/api/projects/{projectId}/context/evidence-coverage", projectId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode group(JsonNode summary, String groupKey) {
        for (JsonNode group : summary.path("sourceGroups")) {
            if (groupKey.equals(group.path("groupKey").asText())) {
                return group;
            }
        }
        throw new AssertionError("Missing source group: " + groupKey);
    }

    private String missingReason(JsonNode summary, String category) {
        for (JsonNode item : summary.path("missingCategories")) {
            if (category.equals(item.path("category").asText())) {
                return item.path("reason").asText();
            }
        }
        throw new AssertionError("Missing category: " + category);
    }

    private void writeCompleteEvidenceFixture() throws Exception {
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        Path resourcesDir = Files.createDirectories(projectRoot.resolve("src/main/resources/db"));
        Files.writeString(resourcesDir.getParent().resolve("application.yml"), "server:\n  port: 18080\n");
        Files.writeString(resourcesDir.resolve("schema.sql"), """
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY
                );
                """);
        Path sourceDir = Files.createDirectories(projectRoot.resolve("src/main/java/com/acme/order"));
        Files.writeString(sourceDir.resolve("OrderService.java"), """
                package com.acme.order;

                public class OrderService {
                    public void create() {
                    }
                }
                """);
        Path testDir = Files.createDirectories(projectRoot.resolve("src/test/java/com/acme/order"));
        Files.writeString(testDir.resolve("OrderServiceTest.java"), """
                package com.acme.order;

                class OrderServiceTest {
                    @org.junit.jupiter.api.Test
                    void createsOrder() {
                    }
                }
                """);
        Files.writeString(projectRoot.resolve("README.md"), "# Order Service\n");
        Files.createDirectories(projectRoot.resolve("docs/design"));
        Files.writeString(projectRoot.resolve("docs/design/order-flow.md"), "# Order Flow\n");
        Path manualDir = Files.createDirectories(projectRoot.resolve(".ai/manual"));
        Files.writeString(manualDir.resolve("business-context.md"), "# Business Context\n");
        Path generatedDir = Files.createDirectories(projectRoot.resolve(".ai/generated"));
        Files.writeString(generatedDir.resolve("tech-architecture.md"), "# Tech Architecture\n");
        Files.writeString(projectRoot.resolve(".ai/code-map.json"), "{}");
        Files.createDirectories(projectRoot.resolve("docs/benchmarks"));
        Files.writeString(projectRoot.resolve("docs/benchmarks/load-test.md"), "# Load Test\n");
        Files.createDirectories(projectRoot.resolve("docs/reports"));
        Files.writeString(projectRoot.resolve("docs/reports/review-report.md"), "# Review Report\n");
    }
}
