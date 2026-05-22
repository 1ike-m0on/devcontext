package com.devcontext.domain.context;

import java.time.Instant;

public record ContextAssetWriteReport(
        String type,
        String relativePath,
        boolean generated,
        boolean manual,
        boolean exists,
        boolean written,
        boolean skipped,
        String status,
        Instant updatedAt
) {
}
