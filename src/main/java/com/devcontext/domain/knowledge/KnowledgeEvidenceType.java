package com.devcontext.domain.knowledge;

import com.devcontext.domain.evidence.EvidenceSourceKind;
import com.devcontext.domain.evidence.EvidenceSourceReliability;
import com.devcontext.domain.evidence.EvidenceType;
import java.util.Optional;

public enum KnowledgeEvidenceType {
    GENERATED_DOC(EvidenceType.GENERATED_DOC),
    MANUAL_DOC(EvidenceType.MANUAL_DOC),
    CODE_MAP(EvidenceType.CODE_MAP),
    SQL_SCHEMA(EvidenceType.SQL_SCHEMA),
    MAPPER(EvidenceType.MAPPER),
    CONFIG(EvidenceType.CONFIG),
    DEPLOYMENT(EvidenceType.DEPLOYMENT),
    OBSERVABILITY(EvidenceType.OBSERVABILITY),
    TEST(EvidenceType.TEST),
    BENCHMARK(EvidenceType.BENCHMARK),
    CI(EvidenceType.CI),
    API_CONTROLLER(EvidenceType.API_CONTROLLER),
    SERVICE_CODE(EvidenceType.SERVICE_CODE),
    QUEUE(EvidenceType.QUEUE),
    CACHE(EvidenceType.CACHE),
    SECURITY(EvidenceType.SECURITY);

    private final EvidenceType taxonomyType;

    KnowledgeEvidenceType(EvidenceType taxonomyType) {
        this.taxonomyType = taxonomyType;
    }

    public String canonicalName() {
        return taxonomyType.canonicalName();
    }

    public EvidenceType taxonomyType() {
        return taxonomyType;
    }

    public EvidenceSourceKind sourceKind() {
        return taxonomyType.sourceKind();
    }

    public EvidenceSourceReliability sourceReliability() {
        return taxonomyType.sourceReliability();
    }

    public static Optional<KnowledgeEvidenceType> normalize(String value) {
        return EvidenceType.normalize(value).map(KnowledgeEvidenceType::fromTaxonomyType);
    }

    public static KnowledgeEvidenceType fromTaxonomyType(EvidenceType evidenceType) {
        return KnowledgeEvidenceType.valueOf(evidenceType.name());
    }
}
