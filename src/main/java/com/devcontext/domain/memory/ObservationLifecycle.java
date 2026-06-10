package com.devcontext.domain.memory;

public enum ObservationLifecycle {
    RAW("raw"),
    CLASSIFIED("classified"),
    CANDIDATE("candidate"),
    ARCHIVED("archived");

    private final String value;

    ObservationLifecycle(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
