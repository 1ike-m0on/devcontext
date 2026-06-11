package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.application.knowledge.KnowledgeFusionScoringService;
import com.devcontext.domain.knowledge.KnowledgeChunk;
import com.devcontext.domain.knowledge.KnowledgeChunkView;
import com.devcontext.domain.knowledge.KnowledgeDocument;
import com.devcontext.domain.knowledge.KnowledgeEvidenceType;
import com.devcontext.domain.knowledge.KnowledgeFusionScore;
import com.devcontext.domain.knowledge.KnowledgeQueryPlan;
import com.devcontext.domain.knowledge.KnowledgeSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeFusionScoringServiceTests {

    private final KnowledgeFusionScoringService scoringService = new KnowledgeFusionScoringService();

    @Test
    void requiredEvidenceOutranksGenericDocEvenWhenBaseScoreIsLower() {
        KnowledgeQueryPlan plan = plan(
                List.of(KnowledgeEvidenceType.BENCHMARK),
                List.of(KnowledgeEvidenceType.BENCHMARK, KnowledgeEvidenceType.OBSERVABILITY)
        );

        KnowledgeFusionScore benchmark = scoringService.score(
                0.20,
                0.20,
                plan,
                List.of(KnowledgeEvidenceType.BENCHMARK),
                view("reports/benchmark/load-test.md", "Load Test", "Latency", "p95 latency is 120ms")
        );
        KnowledgeFusionScore generic = scoringService.score(
                1.0,
                1.0,
                plan,
                List.of(KnowledgeEvidenceType.GENERATED_DOC),
                view(".ai/generated/performance-summary.md", "Performance Summary", "Overview", "Performance latency summary")
        );

        assertThat(benchmark.fusedScore()).isGreaterThan(generic.fusedScore());
        assertThat(benchmark.reasons()).contains(
                "required_evidence:BENCHMARK",
                "preferred_evidence:BENCHMARK",
                "source_reliability:primary",
                "specific_engineering_evidence:benchmark_file"
        );
        assertThat(generic.reasons()).contains("source_reliability:derived", "generic_doc_penalty");
    }

    @Test
    void primaryEngineeringEvidenceOutranksDerivedSummaryWhenScoresAreComparable() {
        KnowledgeQueryPlan plan = plan(
                List.of(),
                List.of(KnowledgeEvidenceType.SERVICE_CODE, KnowledgeEvidenceType.GENERATED_DOC)
        );

        KnowledgeFusionScore serviceCode = scoringService.score(
                0.55,
                0.55,
                plan,
                List.of(KnowledgeEvidenceType.SERVICE_CODE),
                view("src/main/java/com/acme/order/OrderService.java", "OrderService", "createOrder", "@Service create order")
        );
        KnowledgeFusionScore generatedSummary = scoringService.score(
                0.70,
                0.70,
                plan,
                List.of(KnowledgeEvidenceType.GENERATED_DOC),
                view(".ai/generated/order-overview.md", "Order Overview", "createOrder", "Generated create order summary")
        );

        assertThat(serviceCode.fusedScore()).isGreaterThan(generatedSummary.fusedScore());
        assertThat(serviceCode.reasons()).contains(
                "preferred_evidence:SERVICE_CODE",
                "source_reliability:primary",
                "specific_engineering_evidence:service_file"
        );
        assertThat(generatedSummary.reasons()).contains("source_reliability:derived");
    }

    @Test
    void manualAndGeneratedDocsRemainValidFallbackWhenPlanPrefersDocs() {
        KnowledgeQueryPlan plan = plan(
                List.of(),
                List.of(KnowledgeEvidenceType.MANUAL_DOC, KnowledgeEvidenceType.GENERATED_DOC)
        );

        KnowledgeFusionScore manualDoc = scoringService.score(
                0.30,
                0.30,
                plan,
                List.of(KnowledgeEvidenceType.MANUAL_DOC),
                view(".ai/manual/runbook.md", "Runbook", "Local Run", "Run with docker compose")
        );
        KnowledgeFusionScore generatedDoc = scoringService.score(
                0.30,
                0.30,
                plan,
                List.of(KnowledgeEvidenceType.GENERATED_DOC),
                view(".ai/generated/overview.md", "Overview", "Local Run", "Generated local run overview")
        );

        assertThat(manualDoc.fusedScore()).isGreaterThan(generatedDoc.fusedScore());
        assertThat(manualDoc.reasons()).contains("preferred_evidence:MANUAL_DOC", "source_reliability:secondary");
        assertThat(generatedDoc.reasons()).contains("preferred_evidence:GENERATED_DOC", "source_reliability:derived");
        assertThat(manualDoc.reasons()).doesNotContain("generic_doc_penalty");
        assertThat(generatedDoc.reasons()).doesNotContain("generic_doc_penalty");
    }

    private KnowledgeQueryPlan plan(
            List<KnowledgeEvidenceType> requiredEvidenceTypes,
            List<KnowledgeEvidenceType> preferredEvidenceTypes
    ) {
        return new KnowledgeQueryPlan(
                "query",
                "query",
                List.of(),
                requiredEvidenceTypes,
                preferredEvidenceTypes,
                List.of(),
                "evidence_grounded",
                requiredEvidenceTypes.isEmpty() ? "require_retrieved_context" : "require_specific_evidence"
        );
    }

    private KnowledgeChunkView view(String filePath, String title, String headingPath, String content) {
        Instant now = Instant.EPOCH;
        KnowledgeSource source = new KnowledgeSource(1L, "test source", "/tmp/source", "markdown_dir", "indexed", now, now);
        KnowledgeDocument document = new KnowledgeDocument(1L, 1L, filePath, title, "doc-hash", "indexed", now, now, now);
        KnowledgeChunk chunk = new KnowledgeChunk(1L, 1L, 1L, 0, headingPath, content, "chunk-hash", 32, "vector-1", now);
        return new KnowledgeChunkView(chunk, document, source);
    }
}
