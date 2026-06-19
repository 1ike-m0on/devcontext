package com.devcontext.benchmark.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QueryUnderstandingBenchmarkSuite {

    @Test
    void queryUnderstandingRegressionSuitePasses() {
        ContextBenchmarkRunner.RunOutcome outcome = new ContextBenchmarkRunner()
                .run(ContextBenchmarkRunConfig.forSuite("query-understanding"));
        assertThat(outcome.qualityFailures()).isEmpty();
    }
}
