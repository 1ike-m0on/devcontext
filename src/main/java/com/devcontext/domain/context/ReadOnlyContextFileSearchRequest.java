package com.devcontext.domain.context;

import java.nio.file.Path;

public record ReadOnlyContextFileSearchRequest(
        Long runId,
        Path projectRoot,
        String query,
        ReadOnlyContextBudget budget,
        String purpose
) {
    public ReadOnlyContextFileSearchRequest {
        query = query == null ? "" : query.trim();
        budget = budget == null ? ReadOnlyContextBudget.search(0, 0, 0, 0) : budget;
        purpose = purpose == null ? "" : purpose.trim();
    }
}
