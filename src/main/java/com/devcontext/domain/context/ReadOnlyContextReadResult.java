package com.devcontext.domain.context;

import java.util.List;

public record ReadOnlyContextReadResult(
        String status,
        String reason,
        String relativePath,
        String content,
        boolean budgetLimited,
        int filesRead,
        int charactersReturned,
        int linesReturned,
        List<ReadOnlyContextProviderTrace> traces
) {
    public ReadOnlyContextReadResult {
        status = status == null ? "" : status;
        reason = reason == null ? "" : reason;
        relativePath = relativePath == null ? "" : relativePath;
        content = content == null ? "" : content;
        traces = traces == null ? List.of() : List.copyOf(traces);
    }

    public boolean finished() {
        return "finished".equals(status);
    }
}
