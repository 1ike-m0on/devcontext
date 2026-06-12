package com.devcontext.domain.review;

import com.devcontext.domain.run.AgentEvent;
import java.util.List;

public record ReviewEventDetail(
        Long reviewId,
        Long runId,
        List<AgentEvent> events,
        List<ReviewMemorySignal> reviewMemorySignals
) {
    public ReviewEventDetail(Long reviewId, Long runId, List<AgentEvent> events) {
        this(reviewId, runId, events, List.of());
    }
}
