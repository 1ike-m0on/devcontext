package com.devcontext.benchmark.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ContextBenchmarkRunDirectory {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper objectMapper;

    public ContextBenchmarkRunDirectory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path prepare(ContextBenchmarkRunConfig config) {
        Path base = Path.of("target", "context-benchmark-runs").toAbsolutePath().normalize();
        if (config.statsOnly()) {
            if (config.runDir() == null) {
                throw new IllegalArgumentException("StatsOnly requires -RunDir/contextBenchmark.runDir");
            }
            Path runDir = config.runDir().toAbsolutePath().normalize();
            ensureRunDirUnderBase(base, runDir);
            if (!Files.isDirectory(runDir)) {
                throw new IllegalArgumentException("StatsOnly runDir does not exist: " + runDir);
            }
            return runDir;
        }
        Path runDir = config.runDir() == null
                ? base.resolve(FORMATTER.format(LocalDateTime.now()) + "--" + config.runName())
                : config.runDir().toAbsolutePath().normalize();
        ensureRunDirUnderBase(base, runDir);
        try {
            Files.createDirectories(runDir.resolve("cases"));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(runDir.resolve("run-config.json").toFile(), config);
            Files.writeString(base.resolve("context-benchmark-latest.txt"), runDir.toString());
            return runDir;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare context benchmark run directory: " + runDir, e);
        }
    }

    private void ensureRunDirUnderBase(Path base, Path runDir) {
        if (!runDir.startsWith(base)) {
            throw new IllegalArgumentException("Context benchmark runDir must stay under " + base + ": " + runDir);
        }
    }
}
