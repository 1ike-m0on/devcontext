package com.devcontext.domain.review;

import com.devcontext.domain.context.ContextItem;

public record ReviewContextSource(
        String type,
        String title,
        String source,
        int priority,
        int tokenEstimate
) {
    public static ReviewContextSource from(ContextItem item) {
        return new ReviewContextSource(
                item.type(),
                item.title(),
                item.source(),
                item.priority(),
                item.tokenEstimate()
        );
    }
}
