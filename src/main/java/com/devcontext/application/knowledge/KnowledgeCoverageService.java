package com.devcontext.application.knowledge;

import com.devcontext.domain.knowledge.EvidenceCoverageReport;
import com.devcontext.domain.knowledge.KnowledgeChunkView;
import com.devcontext.domain.knowledge.KnowledgeEvidenceType;
import com.devcontext.ports.knowledge.KnowledgeChunkRepository;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeCoverageService {

    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeEvidenceClassifier evidenceClassifier;

    public KnowledgeCoverageService(
            KnowledgeChunkRepository chunkRepository,
            KnowledgeEvidenceClassifier evidenceClassifier
    ) {
        this.chunkRepository = chunkRepository;
        this.evidenceClassifier = evidenceClassifier;
    }

    public EvidenceCoverageReport buildReport(Long sourceId) {
        List<KnowledgeChunkView> views = chunkRepository.findViewsBySourceId(sourceId);
        Map<KnowledgeEvidenceType, Integer> coverage = new EnumMap<>(KnowledgeEvidenceType.class);
        Set<Long> documentIds = new LinkedHashSet<>();

        for (KnowledgeChunkView view : views) {
            documentIds.add(view.document().id());
            List<KnowledgeEvidenceType> evidenceTypes = evidenceClassifier.classify(view);
            for (KnowledgeEvidenceType evidenceType : evidenceTypes) {
                coverage.merge(evidenceType, 1, Integer::sum);
            }
        }

        return new EvidenceCoverageReport(
                sourceId,
                documentIds.size(),
                views.size(),
                coverage,
                warnings(coverage)
        );
    }

    private List<String> warnings(Map<KnowledgeEvidenceType, Integer> coverage) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        if (!coverage.containsKey(KnowledgeEvidenceType.MANUAL_DOC)) {
            warnings.add("No manual business docs found; business-flow answers should be treated as code-structure inference.");
        }
        if (!coverage.containsKey(KnowledgeEvidenceType.BENCHMARK)) {
            warnings.add("No benchmark/runtime evidence found; performance answers should not claim measured production results.");
        }
        if (!coverage.containsKey(KnowledgeEvidenceType.SQL_SCHEMA)
                && !coverage.containsKey(KnowledgeEvidenceType.MAPPER)) {
            warnings.add("No SQL schema or mapper evidence found; database-detail answers may be incomplete.");
        }
        if (!coverage.containsKey(KnowledgeEvidenceType.OBSERVABILITY)) {
            warnings.add("No observability evidence found; monitoring answers may be incomplete.");
        }
        return warnings.stream().toList();
    }
}
