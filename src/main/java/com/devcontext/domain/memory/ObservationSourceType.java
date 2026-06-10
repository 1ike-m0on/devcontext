package com.devcontext.domain.memory;

public enum ObservationSourceType {
    AGENT_RUN("agent_run"),
    AGENT_EVENT("agent_event"),
    RETRIEVAL_RECORD("retrieval_record"),
    REVIEW_RECORD("review_record"),
    REVIEW_ISSUE("review_issue"),
    REVIEW_FEEDBACK("review_feedback"),
    DECISION_REUSE_FEEDBACK("decision_reuse_feedback"),
    BENCHMARK_REPORT("benchmark_report"),
    CONTEXT_REPORT("context_report");

    private final String value;

    ObservationSourceType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
