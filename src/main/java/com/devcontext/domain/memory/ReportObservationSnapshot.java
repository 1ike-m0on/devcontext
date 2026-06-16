package com.devcontext.domain.memory;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ReportObservationSnapshot(
        Long projectId,
        ObservationSourceType sourceType,
        String reportRunId,
        String suite,
        String reportType,
        String taskType,
        String provider,
        String modelName,
        String status,
        String failureCategory,
        String messageSummary,
        String reportPath,
        Instant generatedAt,
        Map<String, Object> summaryMetrics
) {

    public ReportObservationSnapshot {
        sourceType = sourceType == null ? ObservationSourceType.BENCHMARK_REPORT : sourceType;
        summaryMetrics = summaryMetrics == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(summaryMetrics));
    }
}
