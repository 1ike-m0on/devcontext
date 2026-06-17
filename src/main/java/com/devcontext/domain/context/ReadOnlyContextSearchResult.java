package com.devcontext.domain.context;

import java.util.List;

public record ReadOnlyContextSearchResult(
        String status,
        String reason,
        List<ReadOnlyContextSearchMatch> matches,
        boolean budgetLimited,
        int filesRead,
        int charactersReturned,
        int linesReturned,
        List<ReadOnlyContextProviderTrace> traces
) {
    public ReadOnlyContextSearchResult {
        status = status == null ? "" : status;
        reason = reason == null ? "" : reason;
        matches = matches == null ? List.of() : List.copyOf(matches);
        traces = traces == null ? List.of() : List.copyOf(traces);
    }
}
