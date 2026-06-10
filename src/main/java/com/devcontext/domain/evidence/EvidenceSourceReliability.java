package com.devcontext.domain.evidence;

public enum EvidenceSourceReliability {
    PRIMARY("primary"),
    SECONDARY("secondary"),
    DERIVED("derived"),
    UNKNOWN("unknown");

    private final String value;

    EvidenceSourceReliability(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
