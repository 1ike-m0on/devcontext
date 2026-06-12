package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.application.review.context.ProjectProfileReviewContextProvider;
import com.devcontext.application.review.context.ReviewContextRequest;
import com.devcontext.domain.context.ContextItem;
import com.devcontext.domain.git.GitDiff;
import com.devcontext.domain.profile.ProjectProfile;
import com.devcontext.domain.profile.ProjectProfileFact;
import com.devcontext.domain.profile.ProjectProfileSourceReference;
import com.devcontext.domain.project.Project;
import com.devcontext.ports.profile.ProjectProfileRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectProfileReviewContextProviderTests {

    private final InMemoryProjectProfileRepository repository = new InMemoryProjectProfileRepository();
    private final ProjectProfileReviewContextProvider provider = new ProjectProfileReviewContextProvider(repository);

    @Test
    void rendersHighSignalProfileFactsWithSourceMetadata() {
        Project project = project(42L);
        repository.upsertByProjectId(profile(
                project.id(),
                "ready",
                List.of(),
                List.of(
                        fact("tech_stack", "Spring Boot", "Spring Boot 3.5 + Java 21",
                                source(".ai/code-map.json", "CODE_MAP", "code_structure", "derived")),
                        fact("module", "review", "src/main/java/com/devcontext/application/review - AI code review flow",
                                source(".ai/code-map.json", "CODE_MAP", "code_structure", "derived")),
                        fact("endpoint", "POST /api/projects/{projectId}/reviews", "ReviewController#create",
                                source("src/main/java/com/devcontext/adapters/web/ReviewController.java", "API_CONTROLLER", "api_surface", "primary")),
                        fact("evidence_coverage", "SQL_SCHEMA", "chunks=3; sourceId=7",
                                source("src/main/resources/db/schema.sql", "SQL_SCHEMA", "data_schema", "primary")),
                        fact("test_strategy", "review service tests", "Use focused MockMvc review tests with mock LLM.",
                                source("src/test/java/com/devcontext/Mvp2AiCodeReviewTests.java", "TEST", "test_artifact", "primary")),
                        fact("context_asset", "BUSINESS_CONTEXT", ".ai/manual/business-context.md status=written",
                                source(".ai/manual/business-context.md", "MANUAL_DOC", "documentation", "secondary")),
                        fact("evidence_coverage", "BENCHMARK", "chunks=12; sourceId=9",
                                source("docs/benchmarks/review.md", "BENCHMARK", "benchmark_report", "primary"))
                )
        ));

        List<ContextItem> items = provider.provide(new ReviewContextRequest(project, diff()));

        assertThat(items).hasSize(1);
        ContextItem item = items.get(0);
        assertThat(item.type()).isEqualTo("PROJECT_PROFILE_FACTS");
        assertThat(item.priority()).isEqualTo(830);
        assertThat(item.source()).isEqualTo("project-profile:42");
        assertThat(item.tokenEstimate()).isLessThan(900);
        assertThat(item.content())
                .contains("ProjectProfile status=ready")
                .contains("[tech_stack] Spring Boot = Spring Boot 3.5 + Java 21 | sourcePath=.ai/code-map.json evidenceType=CODE_MAP sourceKind=code_structure reliability=derived")
                .contains("[module] review = src/main/java/com/devcontext/application/review - AI code review flow")
                .contains("[endpoint] POST /api/projects/{projectId}/reviews = ReviewController#create | sourcePath=src/main/java/com/devcontext/adapters/web/ReviewController.java evidenceType=API_CONTROLLER sourceKind=api_surface reliability=primary")
                .contains("[evidence_coverage] SQL_SCHEMA = chunks=3; sourceId=7 | sourcePath=src/main/resources/db/schema.sql evidenceType=SQL_SCHEMA sourceKind=data_schema reliability=primary")
                .contains("[test_strategy] review service tests = Use focused MockMvc review tests with mock LLM.")
                .doesNotContain("BUSINESS_CONTEXT")
                .doesNotContain("BENCHMARK");
    }

    @Test
    void returnsNoContextWhenProfileDoesNotExist() {
        assertThat(provider.provide(new ReviewContextRequest(project(99L), diff()))).isEmpty();
    }

    @Test
    void degradedProfileStillProvidesAvailableFactsWithoutFailingReview() {
        Project project = project(7L);
        repository.upsertByProjectId(profile(
                project.id(),
                "degraded",
                List.of("Missing .ai/code-map.json; profile uses project metadata and available records only."),
                List.of(fact("tech_stack", "registered_language", "Java",
                        source("D:/projects/demo", "CONFIG", "configuration", "primary")))
        ));

        List<ContextItem> items = provider.provide(new ReviewContextRequest(project, diff()));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).content())
                .contains("ProjectProfile status=degraded")
                .contains("Warnings: Missing .ai/code-map.json")
                .contains("[tech_stack] registered_language = Java");
    }

    private Project project(Long id) {
        Instant now = Instant.now();
        return new Project(id, "profile-review-demo", "D:/projects/demo", "Java", "Spring Boot", "main", now, now);
    }

    private GitDiff diff() {
        return new GitDiff(
                "diff --git a/src/main/java/demo/UserService.java b/src/main/java/demo/UserService.java\n+return user.getName();",
                List.of("src/main/java/demo/UserService.java"),
                "hash",
                false
        );
    }

    private ProjectProfile profile(
            Long projectId,
            String status,
            List<String> warnings,
            List<ProjectProfileFact> facts
    ) {
        Instant now = Instant.now();
        return new ProjectProfile(
                null,
                projectId,
                status,
                "profile summary",
                facts,
                warnings,
                now,
                now,
                now
        );
    }

    private ProjectProfileFact fact(
            String factType,
            String name,
            String value,
            ProjectProfileSourceReference source
    ) {
        return new ProjectProfileFact(factType, name, value, List.of(source));
    }

    private ProjectProfileSourceReference source(
            String sourcePath,
            String evidenceType,
            String sourceKind,
            String sourceReliability
    ) {
        return new ProjectProfileSourceReference(sourcePath, evidenceType, sourceKind, sourceReliability);
    }

    private static class InMemoryProjectProfileRepository implements ProjectProfileRepository {
        private ProjectProfile profile;

        @Override
        public ProjectProfile upsertByProjectId(ProjectProfile profile) {
            this.profile = profile;
            return profile;
        }

        @Override
        public Optional<ProjectProfile> findByProjectId(Long projectId) {
            if (profile == null || !profile.projectId().equals(projectId)) {
                return Optional.empty();
            }
            return Optional.of(profile);
        }
    }
}
