package com.devcontext.domain.review;

import com.devcontext.domain.context.ContextItem;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

public record ReviewContextCoverage(
        int sourceCount,
        int totalTokenEstimate,
        List<String> sourceTypes,
        List<ReviewContextSource> sources,
        boolean reviewRules,
        boolean projectProfile,
        boolean projectGraph,
        boolean reviewMemorySignals,
        boolean decisionMemory
) {
    public static ReviewContextCoverage empty() {
        return from(List.of());
    }

    public static ReviewContextCoverage from(List<ContextItem> contextItems) {
        List<ContextItem> safeItems = contextItems == null ? List.of() : contextItems;
        List<ReviewContextSource> sources = safeItems.stream()
                .map(ReviewContextSource::from)
                .toList();
        List<String> sourceTypes = safeItems.stream()
                .map(ContextItem::type)
                .filter(type -> type != null && !type.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
        int totalTokenEstimate = safeItems.stream()
                .mapToInt(ContextItem::tokenEstimate)
                .sum();
        return new ReviewContextCoverage(
                sources.size(),
                totalTokenEstimate,
                sourceTypes,
                sources,
                sourceTypes.contains("REVIEW_RULES"),
                sourceTypes.contains("PROJECT_PROFILE_FACTS"),
                sourceTypes.contains("PROJECT_GRAPH_NEIGHBORS"),
                sourceTypes.contains("REVIEW_FEEDBACK_MEMORY"),
                sourceTypes.contains("DECISION_MEMORY")
        );
    }
}
