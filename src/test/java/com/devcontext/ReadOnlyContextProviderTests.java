package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.application.context.ReadOnlyContextTraceRecorder;
import com.devcontext.application.context.SandboxedReadOnlyContextProvider;
import com.devcontext.domain.context.ReadOnlyContextBudget;
import com.devcontext.domain.context.ReadOnlyContextFileReadRequest;
import com.devcontext.domain.context.ReadOnlyContextFileSearchRequest;
import com.devcontext.domain.context.ReadOnlyContextProviderTrace;
import com.devcontext.domain.context.ReadOnlyContextReadResult;
import com.devcontext.domain.context.ReadOnlyContextSearchResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadOnlyContextProviderTests {

    @TempDir
    private Path projectRoot;

    private InMemoryTraceRecorder traceRecorder;
    private SandboxedReadOnlyContextProvider provider;

    @BeforeEach
    void setUp() {
        traceRecorder = new InMemoryTraceRecorder();
        provider = new SandboxedReadOnlyContextProvider(traceRecorder);
    }

    @Test
    void allowsSandboxedReadAndSearchInsideProjectRoot() throws Exception {
        writeProjectFile("src/main/java/com/acme/order/OrderService.java", """
                package com.acme.order;

                public class OrderService {
                    public void createOrder() {
                    }
                }
                """);

        ReadOnlyContextReadResult read = provider.readFile(new ReadOnlyContextFileReadRequest(
                42L,
                projectRoot,
                "src/main/java/com/acme/order/OrderService.java",
                ReadOnlyContextBudget.read(1, 1_000, 40),
                "test_read"
        ));
        ReadOnlyContextSearchResult search = provider.searchFiles(new ReadOnlyContextFileSearchRequest(
                42L,
                projectRoot,
                "createOrder",
                ReadOnlyContextBudget.search(5, 10, 1_000, 10),
                "test_search"
        ));

        assertThat(read.status()).isEqualTo("finished");
        assertThat(read.relativePath()).isEqualTo("src/main/java/com/acme/order/OrderService.java");
        assertThat(read.content()).contains("createOrder");
        assertThat(search.status()).isEqualTo("finished");
        assertThat(search.matches())
                .anySatisfy(match -> {
                    assertThat(match.relativePath()).isEqualTo("src/main/java/com/acme/order/OrderService.java");
                    assertThat(match.snippet()).contains("createOrder");
                });
        assertThat(traceRecorder.statuses()).contains("started", "finished");
    }

    @Test
    void rejectsPathTraversalAbsoluteOutOfRootAndUnauthorizedPaths() throws Exception {
        Path outsideFile = Files.createTempFile("read-only-context-outside", ".txt");
        Files.writeString(outsideFile, "outside");
        writeProjectFile(".git/config", "secret");

        ReadOnlyContextReadResult traversal = provider.readFile(new ReadOnlyContextFileReadRequest(
                7L,
                projectRoot,
                "../outside.txt",
                ReadOnlyContextBudget.read(1, 100, 5),
                "traversal"
        ));
        ReadOnlyContextReadResult absoluteOutside = provider.readFile(new ReadOnlyContextFileReadRequest(
                7L,
                projectRoot,
                outsideFile.toString(),
                ReadOnlyContextBudget.read(1, 100, 5),
                "outside"
        ));
        ReadOnlyContextReadResult unauthorized = provider.readFile(new ReadOnlyContextFileReadRequest(
                7L,
                projectRoot,
                ".git/config",
                ReadOnlyContextBudget.read(1, 100, 5),
                "unauthorized"
        ));

        assertThat(traversal.status()).isEqualTo("rejected");
        assertThat(traversal.reason()).isEqualTo("path_traversal_rejected");
        assertThat(absoluteOutside.status()).isEqualTo("rejected");
        assertThat(absoluteOutside.reason()).isEqualTo("absolute_path_out_of_root");
        assertThat(unauthorized.status()).isEqualTo("rejected");
        assertThat(unauthorized.reason()).isEqualTo("unauthorized_path");
        assertThat(traceRecorder.statuses()).contains("rejected");
    }

    @Test
    void limitsReadOutputByCharacterAndLineBudget() throws Exception {
        writeProjectFile("src/main/resources/application.yml", """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost/orders
                  jpa:
                    open-in-view: false
                """);

        ReadOnlyContextReadResult result = provider.readFile(new ReadOnlyContextFileReadRequest(
                11L,
                projectRoot,
                "src/main/resources/application.yml",
                ReadOnlyContextBudget.read(1, 30, 2),
                "budget"
        ));

        assertThat(result.status()).isEqualTo("finished");
        assertThat(result.budgetLimited()).isTrue();
        assertThat(result.charactersReturned()).isLessThanOrEqualTo(30);
        assertThat(result.linesReturned()).isLessThanOrEqualTo(2);
        assertThat(result.traces()).last().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo("finished");
            assertThat(trace.budgetLimited()).isTrue();
            assertThat(trace.charactersReturned()).isLessThanOrEqualTo(30);
        });
    }

    @Test
    void recordsStartedSkippedFinishedAndRejectedProviderTraces() throws Exception {
        writeProjectFile("README.md", "project readme");

        provider.readFile(new ReadOnlyContextFileReadRequest(
                99L,
                projectRoot,
                "README.md",
                ReadOnlyContextBudget.read(1, 100, 5),
                "trace_read"
        ));
        provider.searchFiles(new ReadOnlyContextFileSearchRequest(
                99L,
                projectRoot,
                " ",
                ReadOnlyContextBudget.search(5, 5, 100, 5),
                "trace_skip"
        ));
        provider.readFile(new ReadOnlyContextFileReadRequest(
                99L,
                projectRoot,
                "/not/inside/project.txt",
                ReadOnlyContextBudget.read(1, 100, 5),
                "trace_reject"
        ));

        assertThat(traceRecorder.statuses())
                .contains("started", "finished", "skipped", "rejected");
        assertThat(traceRecorder.traces())
                .allSatisfy(trace -> assertThat(trace.providerName())
                        .isEqualTo(SandboxedReadOnlyContextProvider.PROVIDER_NAME));
    }

    private void writeProjectFile(String relativePath, String content) throws Exception {
        Path file = projectRoot.resolve(relativePath);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, content);
    }

    private static class InMemoryTraceRecorder implements ReadOnlyContextTraceRecorder {

        private final List<ReadOnlyContextProviderTrace> traces = new ArrayList<>();

        @Override
        public void record(ReadOnlyContextProviderTrace trace) {
            traces.add(trace);
        }

        private List<ReadOnlyContextProviderTrace> traces() {
            return traces;
        }

        private List<String> statuses() {
            return traces.stream().map(ReadOnlyContextProviderTrace::status).toList();
        }
    }
}
