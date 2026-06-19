package com.devcontext.benchmark.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContextBenchmarkCliTests {

    @Test
    void runConfiguredContextBenchmarkSuite() {
        if (!Boolean.parseBoolean(System.getProperty("contextBenchmark.invoked", "false"))) {
            return;
        }
        ContextBenchmarkRunner.RunOutcome outcome = new ContextBenchmarkRunner()
                .run(ContextBenchmarkRunConfig.fromSystemProperties());
        assertThat(outcome.qualityFailures())
                .as(() -> "report: " + outcome.runDir() + " failures=" + outcome.qualityFailures())
                .isEmpty();
    }
}
