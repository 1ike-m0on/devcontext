package com.devcontext.domain.review;

public enum ReviewMemorySignalType {
    CONFIRMED_ISSUE_PATTERN("confirmed_issue_pattern"),
    FALSE_POSITIVE_PATTERN("false_positive_pattern");

    private final String id;

    ReviewMemorySignalType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
