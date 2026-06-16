package com.devcontext.domain.memory;

import java.util.Arrays;
import java.util.Optional;

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

    public static Optional<ObservationLifecycle> fromValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim();
        return Arrays.stream(values())
                .filter(lifecycle -> lifecycle.value.equalsIgnoreCase(normalized))
                .findFirst();
    }
}
