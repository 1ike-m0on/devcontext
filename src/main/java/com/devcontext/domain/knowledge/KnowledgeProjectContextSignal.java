package com.devcontext.domain.knowledge;

import java.util.List;

public record KnowledgeProjectContextSignal(
        Long projectId,
        List<String> graphMatches,
        List<String> profileMatches
) {

    public static KnowledgeProjectContextSignal none() {
        return new KnowledgeProjectContextSignal(null, List.of(), List.of());
    }

    public KnowledgeProjectContextSignal {
        graphMatches = graphMatches == null ? List.of() : List.copyOf(graphMatches);
        profileMatches = profileMatches == null ? List.of() : List.copyOf(profileMatches);
    }

    public boolean hasGraphMatches() {
        return !graphMatches.isEmpty();
    }

    public boolean hasProfileMatches() {
        return !profileMatches.isEmpty();
    }
}
