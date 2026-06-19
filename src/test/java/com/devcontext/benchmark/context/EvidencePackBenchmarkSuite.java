package com.devcontext.benchmark.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EvidencePackBenchmarkSuite {

    @Test
    void evidencePackRegressionSuitePasses() {
        ContextBenchmarkRunner.RunOutcome outcome = new ContextBenchmarkRunner()
                .run(ContextBenchmarkRunConfig.forSuite("evidence-pack"));
        assertThat(outcome.qualityFailures()).isEmpty();
    }
}
