package com.devcontext.benchmark.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QueryUnderstandingBenchmarkSuiteBoundary {

    @Test
    void boundarySuitePasses() {
        ContextBenchmarkRunner.RunOutcome outcome = new ContextBenchmarkRunner()
                .run(ContextBenchmarkRunConfig.forSuite("boundary"));
        assertThat(outcome.qualityFailures()).isEmpty();
    }
}
