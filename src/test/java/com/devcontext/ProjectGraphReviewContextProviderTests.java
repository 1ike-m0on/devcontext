package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.application.review.context.ProjectGraphReviewContextProvider;
import com.devcontext.application.review.context.ReviewContextRequest;
import com.devcontext.domain.context.ContextItem;
import com.devcontext.domain.git.GitDiff;
import com.devcontext.domain.graph.ProjectGraphEdge;
import com.devcontext.domain.graph.ProjectGraphNode;
import com.devcontext.domain.project.Project;
import com.devcontext.ports.graph.ProjectGraphRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectGraphReviewContextProviderTests {

    private final InMemoryProjectGraphRepository repository = new InMemoryProjectGraphRepository();
    private final ProjectGraphReviewContextProvider provider = new ProjectGraphReviewContextProvider(repository);

    @Test
    void rendersOneHopNeighborsForTouchedFileWithSourceMetadata() {
        Project project = project(42L);
        Instant now = Instant.now();
        repository.replaceProjectGraph(
                project.id(),
                List.of(
                        node(project.id(), "file", "file:src/main/java/com/acme/order/OrderController.java",
                                "OrderController.java", "src/main/java/com/acme/order/OrderController.java", now),
                        node(project.id(), "symbol", "symbol:src/main/java/com/acme/order/OrderController.java#OrderController",
                                "OrderController", "src/main/java/com/acme/order/OrderController.java", now),
                        node(project.id(), "endpoint", "endpoint:POST /api/orders",
                                "POST /api/orders", "src/main/java/com/acme/order/OrderController.java", now),
                        node(project.id(), "module", "module:order",
                                "order", ".ai/code-map.json", now),
                        node(project.id(), "profile_fact", "profile_fact:database:orders",
                                "database: orders table", "src/main/resources/db/schema.sql", now),
                        node(project.id(), "context_asset", "context_asset:.ai/manual/business-context.md",
                                "business-context.md", ".ai/manual/business-context.md", now),
                        node(project.id(), "runtime_component", "runtime_component:worker",
                                "worker", "src/main/java/com/acme/order/Worker.java", now)
                ),
                List.of(
                        edge(project.id(), "declares", "declares:file->symbol",
                                "file:src/main/java/com/acme/order/OrderController.java",
                                "symbol:src/main/java/com/acme/order/OrderController.java#OrderController",
                                "src/main/java/com/acme/order/OrderController.java", now),
                        edge(project.id(), "defined_in", "defined_in:endpoint->file",
                                "endpoint:POST /api/orders",
                                "file:src/main/java/com/acme/order/OrderController.java",
                                "src/main/java/com/acme/order/OrderController.java", now),
                        edge(project.id(), "contains", "contains:module->file",
                                "module:order",
                                "file:src/main/java/com/acme/order/OrderController.java",
                                ".ai/code-map.json", now),
                        edge(project.id(), "supported_by", "supported_by:fact->file",
                                "profile_fact:database:orders",
                                "file:src/main/java/com/acme/order/OrderController.java",
                                "src/main/resources/db/schema.sql", now),
                        edge(project.id(), "documented_by", "documented_by:file->context",
                                "file:src/main/java/com/acme/order/OrderController.java",
                                "context_asset:.ai/manual/business-context.md",
                                ".ai/manual/business-context.md", now),
                        edge(project.id(), "runs", "runs:file->runtime",
                                "file:src/main/java/com/acme/order/OrderController.java",
                                "runtime_component:worker",
                                "src/main/java/com/acme/order/Worker.java", now),
                        edge(project.id(), "calls", "calls:symbol->runtime",
                                "symbol:src/main/java/com/acme/order/OrderController.java#OrderController",
                                "runtime_component:worker",
                                "src/main/java/com/acme/order/Worker.java", now)
                )
        );

        List<ContextItem> items = provider.provide(new ReviewContextRequest(project, diff(
                "src/main/java/com/acme/order/OrderController.java"
        )));

        assertThat(items).hasSize(1);
        ContextItem item = items.get(0);
        assertThat(item.type()).isEqualTo("PROJECT_GRAPH_NEIGHBORS");
        assertThat(item.priority()).isEqualTo(810);
        assertThat(item.source()).isEqualTo("project-graph:42");
        assertThat(item.tokenEstimate()).isLessThan(900);
        assertThat(item.content())
                .contains("ProjectGraph one-hop neighbors for code review.")
                .contains("Touched paths: src/main/java/com/acme/order/OrderController.java")
                .contains("Seed [file] OrderController.java sourcePath=src/main/java/com/acme/order/OrderController.java")
                .contains("--declares--> [symbol] OrderController sourcePath=src/main/java/com/acme/order/OrderController.java")
                .contains("<--defined_in-- [endpoint] POST /api/orders sourcePath=src/main/java/com/acme/order/OrderController.java")
                .contains("<--contains-- [module] order sourcePath=.ai/code-map.json")
                .contains("<--supported_by-- [profile_fact] database: orders table sourcePath=src/main/resources/db/schema.sql")
                .contains("--documented_by--> [context_asset] business-context.md sourcePath=.ai/manual/business-context.md")
                .doesNotContain("runtime_component")
                .doesNotContain("calls:symbol->runtime");
    }

    @Test
    void returnsNoContextWhenGraphIsEmpty() {
        assertThat(provider.provide(new ReviewContextRequest(project(7L), diff(
                "src/main/java/com/acme/order/OrderController.java"
        )))).isEmpty();
    }

    @Test
    void returnsNoContextWhenNoSeedNodeMatchesTouchedFiles() {
        Project project = project(9L);
        Instant now = Instant.now();
        repository.replaceProjectGraph(
                project.id(),
                List.of(node(project.id(), "file", "file:src/main/java/com/acme/order/OrderController.java",
                        "OrderController.java", "src/main/java/com/acme/order/OrderController.java", now)),
                List.of()
        );

        assertThat(provider.provide(new ReviewContextRequest(project, diff(
                "src/main/java/com/acme/customer/CustomerController.java"
        )))).isEmpty();
    }

    private Project project(Long id) {
        Instant now = Instant.now();
        return new Project(id, "graph-review-demo", "D:/projects/demo", "Java", "Spring Boot", "main", now, now);
    }

    private GitDiff diff(String changedFile) {
        return new GitDiff(
                "diff --git a/" + changedFile + " b/" + changedFile + System.lineSeparator()
                        + "+return order.id();",
                List.of(changedFile),
                "hash",
                false
        );
    }

    private ProjectGraphNode node(
            Long projectId,
            String nodeType,
            String stableKey,
            String label,
            String sourcePath,
            Instant now
    ) {
        return new ProjectGraphNode(
                null,
                projectId,
                nodeType,
                stableKey,
                label,
                sourcePath,
                "CODE_MAP",
                "code_structure",
                "derived",
                now,
                now
        );
    }

    private ProjectGraphEdge edge(
            Long projectId,
            String edgeType,
            String stableKey,
            String fromNodeKey,
            String toNodeKey,
            String sourcePath,
            Instant now
    ) {
        return new ProjectGraphEdge(
                null,
                projectId,
                edgeType,
                stableKey,
                fromNodeKey,
                toNodeKey,
                edgeType,
                sourcePath,
                "CODE_MAP",
                "code_structure",
                "derived",
                now,
                now
        );
    }

    private static class InMemoryProjectGraphRepository implements ProjectGraphRepository {
        private final List<ProjectGraphNode> nodes = new ArrayList<>();
        private final List<ProjectGraphEdge> edges = new ArrayList<>();

        @Override
        public void replaceProjectGraph(Long projectId, List<ProjectGraphNode> nodes, List<ProjectGraphEdge> edges) {
            this.nodes.clear();
            this.nodes.addAll(nodes);
            this.edges.clear();
            this.edges.addAll(edges);
        }

        @Override
        public List<ProjectGraphNode> findNodesByProjectId(Long projectId) {
            return nodes.stream()
                    .filter(node -> node.projectId().equals(projectId))
                    .toList();
        }

        @Override
        public List<ProjectGraphEdge> findEdgesByProjectId(Long projectId) {
            return edges.stream()
                    .filter(edge -> edge.projectId().equals(projectId))
                    .toList();
        }

        @Override
        public Optional<ProjectGraphNode> findNodeByStableKey(Long projectId, String stableKey) {
            return findNodesByProjectId(projectId).stream()
                    .filter(node -> node.stableKey().equals(stableKey))
                    .findFirst();
        }
    }
}
