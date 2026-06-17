package com.devcontext.domain.context;

import java.nio.file.Path;

public record ReadOnlyContextFileReadRequest(
        Long runId,
        Path projectRoot,
        String path,
        ReadOnlyContextBudget budget,
        String purpose
) {
    public ReadOnlyContextFileReadRequest {
        budget = budget == null ? ReadOnlyContextBudget.read(1, 0, 0) : budget;
        purpose = purpose == null ? "" : purpose.trim();
    }
}
