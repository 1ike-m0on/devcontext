package com.devcontext.application.context;

import com.devcontext.domain.context.ReadOnlyContextBudget;
import com.devcontext.domain.context.ReadOnlyContextFileReadRequest;
import com.devcontext.domain.context.ReadOnlyContextFileSearchRequest;
import com.devcontext.domain.context.ReadOnlyContextProviderTrace;
import com.devcontext.domain.context.ReadOnlyContextReadResult;
import com.devcontext.domain.context.ReadOnlyContextSearchMatch;
import com.devcontext.domain.context.ReadOnlyContextSearchResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SandboxedReadOnlyContextProvider implements ReadOnlyContextProvider {

    public static final String PROVIDER_NAME = "sandboxed_project_read_only_context";

    private static final String OPERATION_READ = "file_read";
    private static final String OPERATION_SEARCH = "file_search";
    private static final Set<String> BLOCKED_PATH_PREFIXES = Set.of(
            ".git/",
            ".gradle/",
            ".idea/",
            ".vscode/",
            "build/",
            "dist/",
            "node_modules/",
            "out/",
            "target/"
    );
    private static final Set<String> SEARCHABLE_EXTENSIONS = Set.of(
            ".java",
            ".kt",
            ".kts",
            ".ts",
            ".tsx",
            ".js",
            ".jsx",
            ".sql",
            ".xml",
            ".yml",
            ".yaml",
            ".properties",
            ".json",
            ".md",
            ".txt",
            ".env",
            ".example"
    );

    private final ReadOnlyContextTraceRecorder traceRecorder;

    public SandboxedReadOnlyContextProvider(ReadOnlyContextTraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    @Override
    public ReadOnlyContextReadResult readFile(ReadOnlyContextFileReadRequest request) {
        ReadOnlyContextBudget budget = request == null ? ReadOnlyContextBudget.read(1, 0, 0) : request.budget();
        List<ReadOnlyContextProviderTrace> traces = new ArrayList<>();
        Long runId = request == null ? null : request.runId();
        String subject = request == null ? "" : safe(request.path());
        if (!hasReadBudget(budget)) {
            ReadOnlyContextProviderTrace skipped = trace(runId, OPERATION_READ, "skipped", "budget_empty", subject, List.of(), budget, 0, 0, 0, 0, false);
            record(traces, skipped);
            return new ReadOnlyContextReadResult("skipped", skipped.reason(), "", "", false, 0, 0, 0, traces);
        }
        Optional<ResolvedRoot> root = resolveRoot(request == null ? null : request.projectRoot());
        if (root.isEmpty()) {
            ReadOnlyContextProviderTrace rejected = trace(runId, OPERATION_READ, "rejected", "project_root_unavailable", subject, List.of(), budget, 0, 0, 0, 0, false);
            record(traces, rejected);
            return new ReadOnlyContextReadResult("rejected", rejected.reason(), "", "", false, 0, 0, 0, traces);
        }
        Optional<ResolvedPath> resolvedPath = resolvePath(root.get().root(), subject);
        if (resolvedPath.isEmpty()) {
            String reason = rejectionReason(root.get().root(), subject);
            ReadOnlyContextProviderTrace rejected = trace(runId, OPERATION_READ, "rejected", reason, subject, List.of(), budget, 0, 0, 0, 0, false);
            record(traces, rejected);
            return new ReadOnlyContextReadResult("rejected", rejected.reason(), "", "", false, 0, 0, 0, traces);
        }
        ResolvedPath path = resolvedPath.get();
        ReadOnlyContextProviderTrace started = trace(runId, OPERATION_READ, "started", "", path.relativePath(), List.of(path.relativePath()), budget, 0, 0, 0, 0, false);
        record(traces, started);

        ReadOutcome read = readBudgeted(path.absolutePath(), budget.maxCharacters(), budget.maxLines());
        ReadOnlyContextProviderTrace finished = trace(
                runId,
                OPERATION_READ,
                "finished",
                read.budgetLimited() ? "budget_limited" : "",
                path.relativePath(),
                List.of(path.relativePath()),
                budget,
                0,
                1,
                read.charactersReturned(),
                read.linesReturned(),
                read.budgetLimited()
        );
        record(traces, finished);
        return new ReadOnlyContextReadResult(
                "finished",
                finished.reason(),
                path.relativePath(),
                read.content(),
                read.budgetLimited(),
                1,
                read.charactersReturned(),
                read.linesReturned(),
                traces
        );
    }

    @Override
    public ReadOnlyContextSearchResult searchFiles(ReadOnlyContextFileSearchRequest request) {
        ReadOnlyContextBudget budget = request == null ? ReadOnlyContextBudget.search(0, 0, 0, 0) : request.budget();
        List<ReadOnlyContextProviderTrace> traces = new ArrayList<>();
        Long runId = request == null ? null : request.runId();
        String query = request == null ? "" : request.query();
        if (query.isBlank()) {
            ReadOnlyContextProviderTrace skipped = trace(runId, OPERATION_SEARCH, "skipped", "query_blank", "", List.of(), budget, 0, 0, 0, 0, false);
            record(traces, skipped);
            return new ReadOnlyContextSearchResult("skipped", skipped.reason(), List.of(), false, 0, 0, 0, traces);
        }
        if (!hasSearchBudget(budget)) {
            ReadOnlyContextProviderTrace skipped = trace(runId, OPERATION_SEARCH, "skipped", "budget_empty", query, List.of(), budget, 0, 0, 0, 0, false);
            record(traces, skipped);
            return new ReadOnlyContextSearchResult("skipped", skipped.reason(), List.of(), false, 0, 0, 0, traces);
        }
        Optional<ResolvedRoot> root = resolveRoot(request == null ? null : request.projectRoot());
        if (root.isEmpty()) {
            ReadOnlyContextProviderTrace rejected = trace(runId, OPERATION_SEARCH, "rejected", "project_root_unavailable", query, List.of(), budget, 0, 0, 0, 0, false);
            record(traces, rejected);
            return new ReadOnlyContextSearchResult("rejected", rejected.reason(), List.of(), false, 0, 0, 0, traces);
        }
        ReadOnlyContextProviderTrace started = trace(runId, OPERATION_SEARCH, "started", "", query, List.of(), budget, 0, 0, 0, 0, false);
        record(traces, started);

        SearchAccumulator accumulator = search(root.get().root(), query, budget);
        ReadOnlyContextProviderTrace finished = trace(
                runId,
                OPERATION_SEARCH,
                "finished",
                accumulator.budgetLimited ? "budget_limited" : "",
                query,
                accumulator.files.stream().toList(),
                budget,
                accumulator.matches.size(),
                accumulator.filesRead,
                accumulator.charactersReturned,
                accumulator.linesReturned,
                accumulator.budgetLimited
        );
        record(traces, finished);
        return new ReadOnlyContextSearchResult(
                "finished",
                finished.reason(),
                accumulator.matches,
                accumulator.budgetLimited,
                accumulator.filesRead,
                accumulator.charactersReturned,
                accumulator.linesReturned,
                traces
        );
    }

    private SearchAccumulator search(Path root, String query, ReadOnlyContextBudget budget) {
        SearchAccumulator accumulator = new SearchAccumulator();
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isAuthorized(root, path))
                    .filter(path -> isSearchableTextPath(relativePath(root, path)))
                    .sorted(Comparator.comparing(path -> relativePath(root, path)))
                    .toList();
        } catch (IOException e) {
            accumulator.budgetLimited = false;
            return accumulator;
        }
        for (Path file : files) {
            if (accumulator.filesRead >= budget.maxFiles()) {
                accumulator.budgetLimited = true;
                break;
            }
            if (accumulator.matches.size() >= budget.maxMatches()
                    || accumulator.charactersReturned >= budget.maxCharacters()
                    || accumulator.linesReturned >= budget.maxLines()) {
                accumulator.budgetLimited = true;
                break;
            }
            String relativePath = relativePath(root, file);
            accumulator.filesRead++;
            if (relativePath.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                addMatch(accumulator, budget, new ReadOnlyContextSearchMatch(relativePath, 0, relativePath));
            }
            searchFileContent(file, relativePath, normalizedQuery, budget, accumulator);
        }
        return accumulator;
    }

    private void searchFileContent(
            Path file,
            String relativePath,
            String query,
            ReadOnlyContextBudget budget,
            SearchAccumulator accumulator
    ) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (accumulator.matches.size() >= budget.maxMatches()
                        || accumulator.charactersReturned >= budget.maxCharacters()
                        || accumulator.linesReturned >= budget.maxLines()) {
                    accumulator.budgetLimited = true;
                    break;
                }
                if (line.toLowerCase(Locale.ROOT).contains(query)) {
                    addMatch(accumulator, budget, new ReadOnlyContextSearchMatch(relativePath, lineNumber, trimSnippet(line)));
                }
            }
        } catch (IOException ignored) {
            // Unreadable files are ignored by search; explicit read calls return a rejected trace.
        }
    }

    private void addMatch(SearchAccumulator accumulator, ReadOnlyContextBudget budget, ReadOnlyContextSearchMatch match) {
        if (accumulator.matches.size() >= budget.maxMatches()
                || accumulator.charactersReturned >= budget.maxCharacters()
                || accumulator.linesReturned >= budget.maxLines()) {
            accumulator.budgetLimited = true;
            return;
        }
        int remainingChars = budget.maxCharacters() - accumulator.charactersReturned;
        String snippet = match.snippet();
        boolean truncated = snippet.length() > remainingChars;
        if (truncated) {
            snippet = snippet.substring(0, remainingChars);
            accumulator.budgetLimited = true;
        }
        accumulator.matches.add(new ReadOnlyContextSearchMatch(match.relativePath(), match.lineNumber(), snippet));
        accumulator.files.add(match.relativePath());
        accumulator.charactersReturned += snippet.length();
        accumulator.linesReturned++;
    }

    private ReadOutcome readBudgeted(Path file, int maxCharacters, int maxLines) {
        StringBuilder content = new StringBuilder();
        int linesReturned = 0;
        boolean budgetLimited = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (linesReturned >= maxLines || content.length() >= maxCharacters) {
                    budgetLimited = true;
                    break;
                }
                String lineWithBreak = line + "\n";
                int remainingCharacters = maxCharacters - content.length();
                if (lineWithBreak.length() > remainingCharacters) {
                    content.append(lineWithBreak, 0, remainingCharacters);
                    linesReturned++;
                    budgetLimited = true;
                    break;
                }
                content.append(lineWithBreak);
                linesReturned++;
            }
            if (!budgetLimited && reader.readLine() != null) {
                budgetLimited = true;
            }
        } catch (IOException e) {
            return new ReadOutcome("", 0, 0, false);
        }
        return new ReadOutcome(content.toString(), content.length(), linesReturned, budgetLimited);
    }

    private Optional<ResolvedRoot> resolveRoot(Path projectRoot) {
        if (projectRoot == null) {
            return Optional.empty();
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedRoot(root));
    }

    private Optional<ResolvedPath> resolvePath(Path root, String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank() || hasTraversalSegment(requestedPath)) {
            return Optional.empty();
        }
        try {
            Path requested = Path.of(requestedPath);
            Path absolute = requested.isAbsolute()
                    ? requested.toAbsolutePath().normalize()
                    : root.resolve(requested).toAbsolutePath().normalize();
            if (!absolute.startsWith(root) || !Files.isRegularFile(absolute) || !isAuthorized(root, absolute)) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedPath(absolute, relativePath(root, absolute)));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private String rejectionReason(Path root, String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) {
            return "path_blank";
        }
        if (hasTraversalSegment(requestedPath)) {
            return "path_traversal_rejected";
        }
        try {
            Path requested = Path.of(requestedPath);
            Path absolute = requested.isAbsolute()
                    ? requested.toAbsolutePath().normalize()
                    : root.resolve(requested).toAbsolutePath().normalize();
            if (!absolute.startsWith(root)) {
                return "absolute_path_out_of_root";
            }
            if (!isAuthorized(root, absolute)) {
                return "unauthorized_path";
            }
            return "file_not_found_or_not_regular";
        } catch (InvalidPathException e) {
            return "invalid_path";
        }
    }

    private boolean hasTraversalSegment(String value) {
        String normalized = value.replace('\\', '/');
        if (normalized.equals("..") || normalized.startsWith("../") || normalized.endsWith("/..")) {
            return true;
        }
        return normalized.contains("/../");
    }

    private boolean isAuthorized(Path root, Path file) {
        String relative = relativePath(root, file).toLowerCase(Locale.ROOT);
        return BLOCKED_PATH_PREFIXES.stream().noneMatch(relative::startsWith)
                && !relative.contains("/node_modules/");
    }

    private boolean isSearchableTextPath(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith("compose.yml")
                || lower.endsWith("compose.yaml")
                || lower.endsWith("docker-compose.yml")
                || lower.endsWith("docker-compose.yaml")
                || lower.endsWith(".env.example")) {
            return true;
        }
        return SEARCHABLE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private String relativePath(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private String trimSnippet(String line) {
        String trimmed = line == null ? "" : line.trim();
        return trimmed.length() > 240 ? trimmed.substring(0, 240) : trimmed;
    }

    private boolean hasReadBudget(ReadOnlyContextBudget budget) {
        return budget.maxFiles() > 0 && budget.maxCharacters() > 0 && budget.maxLines() > 0;
    }

    private boolean hasSearchBudget(ReadOnlyContextBudget budget) {
        return budget.maxMatches() > 0
                && budget.maxFiles() > 0
                && budget.maxCharacters() > 0
                && budget.maxLines() > 0;
    }

    private ReadOnlyContextProviderTrace trace(
            Long runId,
            String operation,
            String status,
            String reason,
            String subject,
            List<String> files,
            ReadOnlyContextBudget budget,
            int matchesReturned,
            int filesRead,
            int charactersReturned,
            int linesReturned,
            boolean budgetLimited
    ) {
        return new ReadOnlyContextProviderTrace(
                runId,
                PROVIDER_NAME,
                operation,
                status,
                reason,
                subject,
                files,
                budget.maxMatches(),
                budget.maxFiles(),
                budget.maxCharacters(),
                budget.maxLines(),
                matchesReturned,
                filesRead,
                charactersReturned,
                linesReturned,
                budgetLimited
        );
    }

    private void record(List<ReadOnlyContextProviderTrace> traces, ReadOnlyContextProviderTrace trace) {
        traces.add(trace);
        traceRecorder.record(trace);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record ResolvedRoot(Path root) {
    }

    private record ResolvedPath(Path absolutePath, String relativePath) {
    }

    private record ReadOutcome(
            String content,
            int charactersReturned,
            int linesReturned,
            boolean budgetLimited
    ) {
    }

    private static final class SearchAccumulator {
        private final List<ReadOnlyContextSearchMatch> matches = new ArrayList<>();
        private final LinkedHashSet<String> files = new LinkedHashSet<>();
        private int filesRead;
        private int charactersReturned;
        private int linesReturned;
        private boolean budgetLimited;
    }
}
