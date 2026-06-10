package com.devcontext.domain.evidence;

public enum EvidenceSourceKind {
    DOCUMENTATION("documentation"),
    CODE_STRUCTURE("code_structure"),
    DATA_SCHEMA("data_schema"),
    DATA_ACCESS("data_access"),
    CONFIGURATION("configuration"),
    DEPLOYMENT("deployment"),
    OBSERVABILITY("observability"),
    TEST_ARTIFACT("test_artifact"),
    BENCHMARK_REPORT("benchmark_report"),
    CI_PIPELINE("ci_pipeline"),
    API_SURFACE("api_surface"),
    SOURCE_CODE("source_code"),
    MESSAGE_QUEUE("message_queue"),
    CACHE("cache"),
    SECURITY("security");

    private final String value;

    EvidenceSourceKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
