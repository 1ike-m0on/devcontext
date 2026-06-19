package com.devcontext.benchmark.context;

import java.nio.file.Path;
import java.util.Locale;

public record ContextBenchmarkRunConfig(
        String suite,
        int caseLimit,
        String keywords,
        String runName,
        Path runDir,
        boolean statsOnly,
        boolean continueRun,
        long seed,
        Path datasetFile
) {
    public ContextBenchmarkRunConfig {
        suite = normalizeSuite(suite);
        keywords = keywords == null ? "" : keywords.trim();
        runName = safeRunName(runName == null || runName.isBlank() ? "source-grounded-context" : runName);
        datasetFile = datasetFile == null ? null : datasetFile.toAbsolutePath().normalize();
        seed = seed <= 0 ? 20260618L : seed;
    }

    public ContextBenchmarkRunConfig(
            String suite,
            int caseLimit,
            String keywords,
            String runName,
            Path runDir,
            boolean statsOnly,
            boolean continueRun,
            long seed
    ) {
        this(suite, caseLimit, keywords, runName, runDir, statsOnly, continueRun, seed, null);
    }

    public static ContextBenchmarkRunConfig fromSystemProperties() {
        boolean all = Boolean.parseBoolean(System.getProperty("contextBenchmark.all", "false"));
        String suite = all ? "all" : System.getProperty("contextBenchmark.suite", "query-understanding");
        int caseLimit = parseInt(System.getProperty("contextBenchmark.caseLimit", "0"));
        String keywords = System.getProperty("contextBenchmark.keywords", "");
        String datasetFileValue = System.getProperty("contextBenchmark.datasetFile", "");
        Path datasetFile = datasetFileValue.isBlank() ? null : Path.of(datasetFileValue);
        String runName = System.getProperty("contextBenchmark.runName", "source-grounded-context");
        String runDirValue = System.getProperty("contextBenchmark.runDir", "");
        Path runDir = runDirValue.isBlank() ? null : Path.of(runDirValue);
        boolean statsOnly = Boolean.parseBoolean(System.getProperty("contextBenchmark.statsOnly", "false"));
        boolean continueRun = Boolean.parseBoolean(System.getProperty("contextBenchmark.continue", "false"));
        long seed = parseLong(System.getProperty("contextBenchmark.seed", "20260618"));
        return new ContextBenchmarkRunConfig(suite, caseLimit, keywords, runName, runDir, statsOnly, continueRun, seed, datasetFile);
    }

    public static ContextBenchmarkRunConfig forSuite(String suite) {
        return new ContextBenchmarkRunConfig(suite, 0, "", "source-grounded-context", null, false, false, 20260618L, null);
    }

    public boolean allSuites() {
        return "all".equals(suite);
    }

    public boolean explicitExternalSuite() {
        return suite.startsWith("external-");
    }

    private static String normalizeSuite(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || "all-no-llm".equals(normalized)) {
            return "all";
        }
        return normalized;
    }

    private static String safeRunName(String value) {
        String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "-");
        cleaned = cleaned.replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return cleaned.isBlank() ? "source-grounded-context" : cleaned;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 20260618L;
        }
    }
}
