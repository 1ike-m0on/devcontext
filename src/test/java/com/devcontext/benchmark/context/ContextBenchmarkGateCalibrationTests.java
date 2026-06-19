package com.devcontext.benchmark.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextBenchmarkGateCalibrationTests {

    private final ContextBenchmarkGates gates = new ContextBenchmarkGates();

    @Test
    void cleanProductEvidencePasses() {
        ContextBenchmarkGates.GateDecision decision = evaluate(
                expected(List.of("src/main/java/example/Controller.java", "src/main/java/example/Service.java"),
                        List.of(), List.of(), 2, 0),
                actual(
                        List.of("src/main/java/example/Controller.java", "src/main/java/example/Service.java"),
                        List.of("docs/diagnostic-hit.md"),
                        List.of("src/main/java/example/Controller.java", "src/main/java/example/Service.java"),
                        List.of(),
                        List.of()
                )
        );

        assertThat(decision.passed()).isTrue();
        assertThat(decision.failureCategory()).isBlank();
        assertThat(decision.productEvidenceCount()).isEqualTo(2);
        assertThat(decision.unexpectedSourcePaths()).isEmpty();
        assertThat(decision.expectedSourceTopNHit()).isTrue();
    }

    @Test
    void noisyProductEvidenceFailsAsProductEvidenceTooLarge() {
        ContextBenchmarkGates.GateDecision decision = evaluate(
                expected(List.of("src/main/java/example/Service.java"), List.of(), List.of(), 1, 10),
                actual(
                        List.of("src/main/java/example/Service.java", "src/main/java/example/Noise.java"),
                        List.of(),
                        List.of("src/main/java/example/Service.java", "src/main/java/example/Noise.java"),
                        List.of(),
                        List.of()
                )
        );

        assertThat(decision.passed()).isFalse();
        assertThat(decision.failureCategory()).isEqualTo("PRODUCT_EVIDENCE_TOO_LARGE");
        assertThat(decision.productEvidenceCount()).isEqualTo(2);
        assertThat(decision.unexpectedSourceCount()).isEqualTo(1);
    }

    @Test
    void expectedSourceOutsideTopNFailsAsExpectedSourceNotTopN() {
        ContextBenchmarkGates.GateDecision decision = evaluate(
                expected(List.of("src/main/java/example/Target.java"), List.of(), List.of(), 2, 10),
                actual(
                        List.of(
                                "src/main/java/example/NoiseA.java",
                                "src/main/java/example/NoiseB.java",
                                "src/main/java/example/Target.java"
                        ),
                        List.of(),
                        List.of(
                                "src/main/java/example/NoiseA.java",
                                "src/main/java/example/NoiseB.java",
                                "src/main/java/example/Target.java"
                        ),
                        List.of(),
                        List.of()
                )
        );

        assertThat(decision.passed()).isFalse();
        assertThat(decision.sourceHit()).isTrue();
        assertThat(decision.expectedSourceTopNHit()).isFalse();
        assertThat(decision.failureCategory()).isEqualTo("EXPECTED_SOURCE_NOT_TOP_N");
    }

    @Test
    void unexpectedSourceThresholdFailsWithoutProductEvidenceSizeFailure() {
        ContextBenchmarkGates.GateDecision decision = evaluate(
                expected(List.of("src/main/java/example/Target.java"), List.of(), List.of(), 3, 0),
                actual(
                        List.of("src/main/java/example/Target.java", "src/main/java/example/Noise.java"),
                        List.of(),
                        List.of("src/main/java/example/Target.java", "src/main/java/example/Noise.java"),
                        List.of(),
                        List.of()
                )
        );

        assertThat(decision.passed()).isFalse();
        assertThat(decision.productEvidenceCount()).isEqualTo(2);
        assertThat(decision.unexpectedSourcePaths()).containsExactly("src/main/java/example/Noise.java");
        assertThat(decision.failureCategory()).isEqualTo("UNEXPECTED_SOURCE_NOISE");
    }

    @Test
    void diagnosticLocatorPathsCannotPassProductSuiteGate() {
        ContextBenchmarkGates.GateDecision decision = evaluate(
                expected(List.of("src/main/java/example/Target.java"), List.of(), List.of(), 2, 0),
                actual(
                        List.of("src/main/java/example/Wrong.java"),
                        List.of("src/main/java/example/Target.java"),
                        List.of("src/main/java/example/Wrong.java"),
                        List.of(),
                        List.of()
                )
        );

        assertThat(decision.passed()).isFalse();
        assertThat(decision.sourceHit()).isFalse();
        assertThat(decision.failureCategory()).isEqualTo("SOURCE_WINNER_FAILED");
    }

    @Test
    void allowedExtraSourceIsNotCountedAsUnexpected() {
        ContextBenchmarkGates.GateDecision decision = evaluate(
                expected(
                        List.of("src/main/java/example/Target.java"),
                        List.of("src/main/java/example/AllowedSupport.java"),
                        List.of(),
                        2,
                        0
                ),
                actual(
                        List.of("src/main/java/example/Target.java", "src/main/java/example/AllowedSupport.java"),
                        List.of(),
                        List.of("src/main/java/example/Target.java", "src/main/java/example/AllowedSupport.java"),
                        List.of(),
                        List.of()
                )
        );

        assertThat(decision.passed()).isTrue();
        assertThat(decision.unexpectedSourcePaths()).isEmpty();
        assertThat(decision.unexpectedSourceCount()).isZero();
    }

    @Test
    void forbiddenSourceLeakFailsStably() {
        ContextBenchmarkGates.GateDecision decision = evaluate(
                expected(List.of("src/main/java/example/Target.java"), List.of(), List.of("docs/**"), 3, 10),
                actual(
                        List.of("src/main/java/example/Target.java", "docs/generated-summary.md"),
                        List.of(),
                        List.of("src/main/java/example/Target.java", "docs/generated-summary.md"),
                        List.of(),
                        List.of()
                )
        );

        assertThat(decision.passed()).isFalse();
        assertThat(decision.failureCategory()).isEqualTo("FORBIDDEN_SOURCE_LEAKED");
        assertThat(decision.forbiddenSourceLeak()).isTrue();
        assertThat(decision.forbiddenSourceHits()).containsExactly("docs/generated-summary.md");
    }

    @Test
    void missingEvidenceNegativePassesWhenExpectedMissingGroupIsReported() {
        ContextBenchmarkGates.GateDecision decision = evaluate(
                missingEvidenceExpected("schema"),
                actual(List.of(), List.of("docs/diagnostic.md"), List.of(), List.of("schema"), List.of())
        );

        assertThat(decision.passed()).isTrue();
        assertThat(decision.failureCategory()).isBlank();
        assertThat(decision.missingEvidenceGroups()).containsExactly("schema");
    }

    @Test
    void missingEvidenceNegativeFailsAsAnswerModeWhenExpectedMissingGroupIsNotReported() {
        ContextBenchmarkGates.GateDecision decision = evaluate(
                missingEvidenceExpected("schema"),
                actual(List.of(), List.of(), List.of(), List.of(), List.of())
        );

        assertThat(decision.passed()).isFalse();
        assertThat(decision.failureCategory()).isEqualTo("ANSWER_MODE_FAILED");
    }

    private ContextBenchmarkGates.GateDecision evaluate(
            ContextBenchmarkExpected expected,
            ContextBenchmarkActual actual
    ) {
        return gates.evaluate(new ContextBenchmarkCase(
                "gate-calibration-case",
                "evidence-pack",
                "Where is the target implementation?",
                "en",
                "devcontext",
                "devcontext",
                "product",
                List.of("gate-calibration"),
                expected
        ), actual);
    }

    private ContextBenchmarkExpected expected(
            List<String> sourcePaths,
            List<String> allowedExtraSourcePaths,
            List<String> forbiddenSourcePathPatterns,
            Integer maxProductEvidenceCount,
            Integer maxUnexpectedSourceCount
    ) {
        return new ContextBenchmarkExpected(
                "implementation_detail",
                List.of("test_strategy"),
                List.of(),
                sourcePaths,
                List.of(),
                allowedExtraSourcePaths,
                List.of(),
                List.of(),
                forbiddenSourcePathPatterns,
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                maxProductEvidenceCount,
                maxUnexpectedSourceCount,
                ""
        );
    }

    private ContextBenchmarkExpected missingEvidenceExpected(String group) {
        return new ContextBenchmarkExpected(
                "implementation_detail",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(group),
                null,
                null,
                ""
        );
    }

    private ContextBenchmarkActual actual(
            List<String> productSelectedSourcePaths,
            List<String> diagnosticLocatorPaths,
            List<String> selectedSourcePathsForGate,
            List<String> missingEvidenceGroups,
            List<String> forbiddenSourceHits
    ) {
        return new ContextBenchmarkActual(
                "implementation_detail",
                List.of("SERVICE_CODE"),
                List.of("gate_calibration"),
                List.of(),
                productSelectedSourcePaths,
                diagnosticLocatorPaths,
                selectedSourcePathsForGate,
                Map.of("source", productSelectedSourcePaths),
                List.of(),
                forbiddenSourceHits,
                false,
                false,
                false,
                true,
                missingEvidenceGroups,
                List.of()
        );
    }
}
