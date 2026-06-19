package com.devcontext.benchmark.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ContextBenchmarkStatsAnalyzer {

    private final ObjectMapper objectMapper;
    private final ContextBenchmarkResultWriter writer;

    public ContextBenchmarkStatsAnalyzer(ObjectMapper objectMapper, ContextBenchmarkResultWriter writer) {
        this.objectMapper = objectMapper;
        this.writer = writer;
    }

    public List<ContextBenchmarkResult> analyze(Path runDir) {
        Path casesDir = runDir.resolve("cases");
        if (!Files.isDirectory(casesDir)) {
            throw new IllegalArgumentException("No context benchmark cases directory found: " + casesDir);
        }
        try (var stream = Files.list(casesDir)) {
            List<ContextBenchmarkResult> results = stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(this::readResult)
                    .sorted(java.util.Comparator.comparing(ContextBenchmarkResult::generatedAt)
                            .thenComparing(ContextBenchmarkResult::caseId))
                    .toList();
            writer.writeSummary(runDir, results);
            return results;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to analyze context benchmark run: " + runDir, e);
        }
    }

    private ContextBenchmarkResult readResult(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), ContextBenchmarkResult.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read context benchmark case result: " + path, e);
        }
    }
}
