package com.devcontext.domain.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.domain.knowledge.KnowledgeEvidenceType;
import org.junit.jupiter.api.Test;

class EvidenceTypeTests {

    @Test
    void normalizesKnownAliasesToStableEvidenceTypes() {
        assertThat(EvidenceType.normalize("SQL_SCHEMA")).contains(EvidenceType.SQL_SCHEMA);
        assertThat(EvidenceType.normalize("sql-schema")).contains(EvidenceType.SQL_SCHEMA);
        assertThat(EvidenceType.normalize("database schema")).contains(EvidenceType.SQL_SCHEMA);
        assertThat(EvidenceType.normalize("redisLua")).contains(EvidenceType.CACHE);
        assertThat(EvidenceType.normalize("github actions")).contains(EvidenceType.CI);
        assertThat(EvidenceType.normalize("")).isEmpty();
    }

    @Test
    void exposesMinimalSourceKindAndReliabilityNames() {
        assertThat(EvidenceType.SQL_SCHEMA.sourceKind().value()).isEqualTo("data_schema");
        assertThat(EvidenceType.SQL_SCHEMA.sourceReliability().value()).isEqualTo("primary");
        assertThat(EvidenceType.MANUAL_DOC.sourceKind().value()).isEqualTo("documentation");
        assertThat(EvidenceType.MANUAL_DOC.sourceReliability().value()).isEqualTo("secondary");
        assertThat(EvidenceType.GENERATED_DOC.sourceReliability().value()).isEqualTo("derived");
    }

    @Test
    void keepsKnowledgeEvidenceTypeApiNamesStable() {
        assertThat(KnowledgeEvidenceType.normalize("database_schema"))
                .contains(KnowledgeEvidenceType.SQL_SCHEMA);
        assertThat(KnowledgeEvidenceType.SQL_SCHEMA.canonicalName()).isEqualTo("SQL_SCHEMA");
        assertThat(KnowledgeEvidenceType.CACHE.sourceKind().value()).isEqualTo("cache");
        assertThat(KnowledgeEvidenceType.OBSERVABILITY.taxonomyType())
                .isEqualTo(EvidenceType.OBSERVABILITY);
    }
}
