package com.devcontext.benchmark.context;

import java.util.Set;

public interface ExternalBenchmarkAdapter {

    Set<String> RESERVED_SUITES = Set.of(
            "external-aider-polyglot",
            "external-repobench",
            "external-multi-swe-bench",
            "external-swe-bench-lite",
            "external-defects4j"
    );

    String suiteName();

    ExternalBenchmarkLoadResult load(ContextBenchmarkRunConfig config);
}
