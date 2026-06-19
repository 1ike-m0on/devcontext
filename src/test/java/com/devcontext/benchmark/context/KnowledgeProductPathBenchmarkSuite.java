package com.devcontext.benchmark.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeProductPathBenchmarkSuite {

    @Test
    void productPathSuiteIsReservedForManualRealLlmAcceptance() {
        assertThat(ExternalBenchmarkAdapter.RESERVED_SUITES)
                .contains("external-multi-swe-bench", "external-swe-bench-lite");
    }
}
