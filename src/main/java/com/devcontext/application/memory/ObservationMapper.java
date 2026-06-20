package com.devcontext.application.memory;

import com.devcontext.domain.decision.DecisionReuseRecord;
import com.devcontext.domain.knowledge.KnowledgeEvidenceType;
import com.devcontext.domain.knowledge.RetrievalRecord;
import com.devcontext.domain.memory.Observation;
import com.devcontext.domain.memory.ObservationLifecycle;
import com.devcontext.domain.memory.ObservationSourceType;
import com.devcontext.domain.memory.ReportObservationSnapshot;
import com.devcontext.domain.review.ReviewIssue;
import com.devcontext.domain.review.ReviewRecord;
import com.devcontext.domain.run.AgentEvent;
import com.devcontext.domain.run.AgentRun;
import com.devcontext.ports.review.ReviewRecordRepository;
import com.devcontext.ports.run.AgentRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ObservationMapper {

    private static final String EMPTY_JSON = "{}";
    private static final String PRIVACY_PUBLIC_METADATA = "public_metadata";
    private static final String PRIVACY_PROJECT_PRIVATE = "project_private";
    private static final String PRIVACY_SENSITIVE_REDACTED = "sensitive_redacted";

    private final AgentRunRepository runRepository;
    private final ReviewRecordRepository reviewRecordRepository;
    private final ObjectMapper objectMapper;
    private final ObservationPrivacySanitizer sanitizer;

    public ObservationMapper(
            AgentRunRepository runRepository,
            ReviewRecordRepository reviewRecordRepository,
            ObjectMapper objectMapper,
            ObservationPrivacySanitizer sanitizer
    ) {
        this.runRepository = runRepository;
        this.reviewRecordRepository = reviewRecordRepository;
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
    }

    public Observation fromAgentRun(AgentRun run) {
        Map<String, Object> metadata = metadata(
                "promptVersion", run.promptVersion(),
                "inputTokenEstimate", run.inputTokenEstimate(),
                "outputTokenEstimate", run.outputTokenEstimate(),
                "durationMs", run.durationMs(),
                "finishedAt", run.finishedAt() == null ? null : run.finishedAt().toString()
        );
        String summary = run.runType() + " " + run.status()
                + optionalMetric("durationMs", run.durationMs())
                + optionalMetric("inputTokens", run.inputTokenEstimate())
                + optionalMetric("outputTokens", run.outputTokenEstimate());
        return observation(
                ObservationSourceType.AGENT_RUN,
                run.id(),
                "agent_run:" + run.id(),
                run.projectId(),
                run.runType(),
                run.status(),
                "Agent run: " + run.runType(),
                summary,
                run.createdAt(),
                run.provider(),
                run.modelName(),
                classifyError(run.errorMessage()),
                run.errorMessage(),
                run.id(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                EMPTY_JSON,
                writeJson(metadata),
                run.errorMessage() == null ? PRIVACY_PUBLIC_METADATA : PRIVACY_SENSITIVE_REDACTED
        );
    }

    public Observation fromAgentEvent(AgentEvent event) {
        Optional<AgentRun> run = run(event.runId());
        Map<String, Object> metadata = metadata(
                "eventType", event.eventType(),
                "durationMs", event.durationMs()
        );
        String summary = joinNonBlank(event.inputSummary(), event.outputSummary());
        return observation(
                ObservationSourceType.AGENT_EVENT,
                event.id(),
                "agent_event:" + event.id(),
                run.map(AgentRun::projectId).orElse(null),
                run.map(AgentRun::runType).orElse("AGENT_EVENT"),
                event.status(),
                event.eventType(),
                summary,
                event.createdAt(),
                run.map(AgentRun::provider).orElse(null),
                run.map(AgentRun::modelName).orElse(null),
                classifyError(event.errorMessage()),
                event.errorMessage(),
                event.runId(),
                event.id(),
                null,
                null,
                null,
                null,
                null,
                null,
                EMPTY_JSON,
                writeJson(metadata),
                event.errorMessage() == null ? PRIVACY_PROJECT_PRIVATE : PRIVACY_SENSITIVE_REDACTED
        );
    }

    public Observation fromRetrievalRecord(RetrievalRecord record) {
        Optional<AgentRun> run = run(record.runId());
        Map<String, Object> metadata = retrievalMetadata(record);
        String summary = "query=" + record.query()
                + "; rewrittenQuery=" + record.rewrittenQuery()
                + "; topK=" + record.topK();
        return observation(
                ObservationSourceType.RETRIEVAL_RECORD,
                record.id(),
                "retrieval_record:" + record.id(),
                run.map(AgentRun::projectId).orElse(null),
                run.map(AgentRun::runType).orElse("KNOWLEDGE_SEARCH"),
                "completed",
                "Knowledge retrieval",
                summary,
                record.createdAt(),
                run.map(AgentRun::provider).orElse(null),
                run.map(AgentRun::modelName).orElse(null),
                null,
                null,
                record.runId(),
                null,
                record.id(),
                null,
                null,
                null,
                null,
                null,
                EMPTY_JSON,
                writeJson(metadata),
                PRIVACY_PROJECT_PRIVATE
        );
    }

    public Observation fromReviewRecord(ReviewRecord record) {
        Optional<AgentRun> run = run(record.runId());
        Map<String, Object> metadata = metadata(
                "baseBranch", record.baseBranch(),
                "compareBranch", record.compareBranch(),
                "diffHash", record.diffHash(),
                "score", record.score(),
                "reportPath", record.reportPath()
        );
        return observation(
                ObservationSourceType.REVIEW_RECORD,
                record.id(),
                "review_record:" + record.id(),
                record.projectId(),
                "AI_CODE_REVIEW",
                "completed",
                "Review " + record.baseBranch() + " -> " + record.compareBranch(),
                record.summary(),
                record.createdAt(),
                run.map(AgentRun::provider).orElse(null),
                run.map(AgentRun::modelName).orElse(null),
                null,
                null,
                record.runId(),
                null,
                null,
                record.id(),
                null,
                null,
                null,
                record.reportPath(),
                EMPTY_JSON,
                writeJson(metadata),
                PRIVACY_PROJECT_PRIVATE
        );
    }

    public Observation fromReviewIssue(ReviewIssue issue) {
        ReviewContext context = reviewContext(issue.reviewId());
        Map<String, Object> metadata = metadata(
                "filePath", sanitizer.metadataText(issue.filePath()),
                "lineNumber", issue.lineNumber(),
                "severity", issue.severity(),
                "confidence", issue.confidence()
        );
        return observation(
                ObservationSourceType.REVIEW_ISSUE,
                issue.id(),
                "review_issue:" + issue.id(),
                context.projectId(),
                "AI_CODE_REVIEW",
                issue.status(),
                issue.severity() + ": " + issue.title(),
                joinNonBlank(issue.description(), issue.impact(), issue.suggestion()),
                issue.createdAt(),
                context.provider(),
                context.modelName(),
                null,
                null,
                context.runId(),
                null,
                null,
                issue.reviewId(),
                issue.id(),
                null,
                null,
                null,
                EMPTY_JSON,
                writeJson(metadata),
                PRIVACY_PROJECT_PRIVATE
        );
    }

    public Observation fromReviewFeedback(ReviewIssue issue) {
        ReviewContext context = reviewContext(issue.reviewId());
        Map<String, Object> metadata = metadata(
                "filePath", sanitizer.metadataText(issue.filePath()),
                "lineNumber", issue.lineNumber(),
                "severity", issue.severity(),
                "confidence", issue.confidence()
        );
        return observation(
                ObservationSourceType.REVIEW_FEEDBACK,
                issue.id(),
                "review_feedback:" + issue.id() + ":" + issue.updatedAt(),
                context.projectId(),
                "REVIEW_FEEDBACK",
                issue.status(),
                "Review feedback: " + issue.title(),
                joinNonBlank(issue.status(), issue.note()),
                issue.updatedAt(),
                context.provider(),
                context.modelName(),
                null,
                null,
                context.runId(),
                null,
                null,
                issue.reviewId(),
                issue.id(),
                null,
                null,
                null,
                EMPTY_JSON,
                writeJson(metadata),
                PRIVACY_PROJECT_PRIVATE
        );
    }

    public Observation fromDecisionReuseFeedback(DecisionReuseRecord record) {
        String feedbackHash = sanitizer.feedbackHash(record.userFeedback());
        Map<String, Object> metadata = metadata(
                "accepted", record.accepted(),
                "matchedDecisionIds", record.matchedDecisionIds(),
                "feedbackHash", feedbackHash
        );
        Optional<AgentRun> run = run(record.runId());
        Instant now = Instant.now();
        return observation(
                ObservationSourceType.DECISION_REUSE_FEEDBACK,
                record.id(),
                "decision_reuse_feedback:" + record.id() + ":" + record.status() + ":" + record.accepted() + ":" + feedbackHash,
                record.projectId(),
                "DECISION_REUSE_FEEDBACK",
                record.status(),
                "Decision reuse feedback",
                record.userFeedback() == null ? "accepted=" + record.accepted() : record.userFeedback(),
                now,
                run.map(AgentRun::provider).orElse(null),
                run.map(AgentRun::modelName).orElse(null),
                null,
                null,
                record.runId(),
                null,
                null,
                null,
                null,
                record.id(),
                null,
                null,
                EMPTY_JSON,
                writeJson(metadata),
                PRIVACY_PROJECT_PRIVATE
        );
    }

    public Observation fromReport(ReportObservationSnapshot report) {
        if (report == null) {
            throw new IllegalArgumentException("report observation snapshot is required");
        }
        Instant generatedAt = report.generatedAt() == null ? Instant.now() : report.generatedAt();
        String reportRunId = firstNonBlank(report.reportRunId(), "report-" + generatedAt.toEpochMilli());
        String suite = firstNonBlank(report.suite(), "report-artifact");
        String reportType = firstNonBlank(report.reportType(), suite);
        String taskType = firstNonBlank(report.taskType(), "REPORT_ARTIFACT");
        String status = firstNonBlank(report.status(), "completed");
        String failureCategory = firstNonBlank(report.failureCategory(), "none");
        boolean failed = !"none".equalsIgnoreCase(failureCategory)
                && !"success".equalsIgnoreCase(failureCategory)
                && !"ok".equalsIgnoreCase(failureCategory);
        Map<String, Object> metadata = metadata(
                "suite", sanitizer.metadataText(suite),
                "reportType", sanitizer.metadataText(reportType),
                "status", sanitizer.metadataText(status),
                "failureCategory", sanitizer.metadataText(failureCategory),
                "generatedAt", generatedAt.toString(),
                "summaryMetrics", sanitizeMetadataValue(report.summaryMetrics())
        );
        if (report.messageSummary() != null && !report.messageSummary().isBlank()) {
            metadata.put("messageSummary", sanitizer.error(report.messageSummary()));
        }

        String summary = joinNonBlank(
                reportType + " " + status,
                "suite=" + suite,
                metricSummary(report.summaryMetrics()),
                failed ? "failureCategory=" + failureCategory : null,
                report.messageSummary()
        );
        return observation(
                report.sourceType(),
                reportRunId,
                report.sourceType().value() + ":" + suite + ":" + reportRunId,
                report.projectId(),
                taskType,
                status,
                "Report artifact: " + reportType,
                summary,
                generatedAt,
                report.provider(),
                report.modelName(),
                failed ? failureCategory : null,
                failed ? report.messageSummary() : null,
                null,
                null,
                null,
                null,
                null,
                null,
                reportRunId,
                report.reportPath(),
                EMPTY_JSON,
                writeJson(metadata),
                failed ? PRIVACY_SENSITIVE_REDACTED : PRIVACY_PROJECT_PRIVATE
        );
    }

    private Observation observation(
            ObservationSourceType sourceType,
            Long sourceRecordId,
            String sourceKey,
            Long projectId,
            String taskType,
            String sourceStatus,
            String title,
            String summary,
            Instant occurredAt,
            String provider,
            String modelName,
            String errorType,
            String errorMessage,
            Long runId,
            Long eventId,
            Long retrievalId,
            Long reviewId,
            Long issueId,
            Long decisionReuseRecordId,
            String reportRunId,
            String reportPath,
            String relationJson,
            String metadataJson,
            String privacyLevel
    ) {
        return observation(
                sourceType,
                sourceRecordId == null ? null : String.valueOf(sourceRecordId),
                sourceKey,
                projectId,
                taskType,
                sourceStatus,
                title,
                summary,
                occurredAt,
                provider,
                modelName,
                errorType,
                errorMessage,
                runId,
                eventId,
                retrievalId,
                reviewId,
                issueId,
                decisionReuseRecordId,
                reportRunId,
                reportPath,
                relationJson,
                metadataJson,
                privacyLevel
        );
    }

    private Observation observation(
            ObservationSourceType sourceType,
            String sourceRecordId,
            String sourceKey,
            Long projectId,
            String taskType,
            String sourceStatus,
            String title,
            String summary,
            Instant occurredAt,
            String provider,
            String modelName,
            String errorType,
            String errorMessage,
            Long runId,
            Long eventId,
            Long retrievalId,
            Long reviewId,
            Long issueId,
            Long decisionReuseRecordId,
            String reportRunId,
            String reportPath,
            String relationJson,
            String metadataJson,
            String privacyLevel
    ) {
        Instant now = Instant.now();
        return new Observation(
                null,
                projectId,
                sourceType.value(),
                sanitizer.metadataText(firstNonBlank(sourceRecordId, sourceKey, "unknown")),
                sourceKey,
                taskType,
                ObservationLifecycle.RAW.value(),
                sourceStatus,
                sanitizer.title(title),
                sanitizer.summary(summary),
                occurredAt == null ? now : occurredAt,
                sanitizer.metadataText(provider),
                sanitizer.metadataText(modelName),
                errorType,
                sanitizer.error(errorMessage),
                runId,
                eventId,
                retrievalId,
                reviewId,
                issueId,
                decisionReuseRecordId,
                sanitizer.metadataText(reportRunId),
                sanitizer.metadataText(reportPath),
                relationJson == null || relationJson.isBlank() ? EMPTY_JSON : relationJson,
                metadataJson == null || metadataJson.isBlank() ? EMPTY_JSON : metadataJson,
                privacyLevel,
                now,
                now
        );
    }

    private Optional<AgentRun> run(Long runId) {
        if (runId == null) {
            return Optional.empty();
        }
        return runRepository.findById(runId);
    }

    private ReviewContext reviewContext(Long reviewId) {
        Optional<ReviewRecord> review = reviewRecordRepository.findById(reviewId);
        Optional<AgentRun> run = review.flatMap(record -> run(record.runId()));
        return new ReviewContext(
                review.map(ReviewRecord::projectId).orElse(null),
                review.map(ReviewRecord::runId).orElse(null),
                run.map(AgentRun::provider).orElse(null),
                run.map(AgentRun::modelName).orElse(null)
        );
    }

    private Map<String, Object> retrievalMetadata(RetrievalRecord record) {
        Map<String, Object> metadata = metadata("topK", record.topK());
        try {
            JsonNode root = objectMapper.readTree(record.resultJson());
            JsonNode results = root;
            if (root.isObject()) {
                JsonNode queryPlanTrace = root.path("queryPlanTrace");
                if (queryPlanTrace.isObject()) {
                    metadata.put("queryPlanTrace", queryPlanTrace(queryPlanTrace));
                }
                results = root.path("results");
            }
            if (results.isArray()) {
                metadata.put("resultCount", results.size());
                List<Map<String, Object>> topResults = new ArrayList<>();
                for (int i = 0; i < Math.min(results.size(), 5); i++) {
                    JsonNode result = results.get(i);
                    topResults.add(metadata(
                            "filePath", sanitizer.metadataText(result.path("filePath").asText(null)),
                            "evidenceTypes", canonicalEvidenceTypes(result.path("evidenceTypes")),
                            "scoreReasons", stringList(result.path("scoreReasons")),
                            "fusedScore", result.path("fusedScore").isMissingNode() ? null : result.path("fusedScore").asDouble()
                    ));
                }
                metadata.put("topResults", topResults);
            }
        } catch (JsonProcessingException e) {
            metadata.put("resultJsonParseError", sanitizer.error(e.getMessage()));
        }
        return metadata;
    }

    private Map<String, Object> queryPlanTrace(JsonNode queryPlanTrace) {
        return metadata(
                "intent", sanitizer.metadataText(queryPlanTrace.path("intent").asText(null)),
                "normalizedTerms", stringList(queryPlanTrace.path("normalizedTerms")),
                "requiredEvidenceTypes", canonicalEvidenceTypes(queryPlanTrace.path("requiredEvidenceTypes")),
                "preferredEvidenceTypes", canonicalEvidenceTypes(queryPlanTrace.path("preferredEvidenceTypes")),
                "requiredSourceKinds", stringList(queryPlanTrace.path("requiredSourceKinds")),
                "preferredSourceKinds", stringList(queryPlanTrace.path("preferredSourceKinds")),
                "forbiddenSourceKinds", stringList(queryPlanTrace.path("forbiddenSourceKinds")),
                "fallbackStrategy", sanitizer.metadataText(queryPlanTrace.path("fallbackStrategy").asText(null)),
                "planningReasons", stringList(queryPlanTrace.path("planningReasons"))
        );
    }

    private Object canonicalEvidenceTypes(JsonNode evidenceTypes) {
        if (evidenceTypes == null || !evidenceTypes.isArray()) {
            return evidenceTypes;
        }
        List<String> canonicalTypes = new ArrayList<>();
        for (JsonNode evidenceType : evidenceTypes) {
            String rawValue = evidenceType.asText(null);
            if (rawValue == null || rawValue.isBlank()) {
                continue;
            }
            canonicalTypes.add(KnowledgeEvidenceType.normalize(rawValue)
                    .map(KnowledgeEvidenceType::canonicalName)
                    .orElse(rawValue.trim()));
        }
        return canonicalTypes;
    }

    private List<String> stringList(JsonNode values) {
        if (values == null || !values.isArray()) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (JsonNode value : values) {
            String text = value.asText(null);
            if (text == null || text.isBlank()) {
                continue;
            }
            strings.add(sanitizer.metadataText(text));
        }
        return strings;
    }

    private Map<String, Object> metadata(Object... entries) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            metadata.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return metadata;
    }

    private String writeJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize observation metadata", e);
        }
    }

    private String joinNonBlank(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                parts.add(value.trim());
            }
        }
        return String.join(" | ", parts);
    }

    private String optionalMetric(String label, Number value) {
        return value == null ? "" : "; " + label + "=" + value;
    }

    private String classifyError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return null;
        }
        String lower = errorMessage.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("unauthorized") || lower.contains("forbidden") || lower.contains("auth") || lower.contains("401") || lower.contains("403")) {
            return "LLM_AUTH_FAILED";
        }
        if (lower.contains("quota") || lower.contains("rate limit") || lower.contains("429")) {
            return "LLM_QUOTA_EXCEEDED";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "LLM_TIMEOUT";
        }
        if (lower.contains("proxy")) {
            return "LLM_PROXY_REQUIRED";
        }
        if (lower.contains("network") || lower.contains("connection") || lower.contains("connect")) {
            return "LLM_NETWORK_FAILED";
        }
        if (lower.contains("parse") || lower.contains("json")) {
            return "LLM_PARSE_FAILED";
        }
        if (lower.contains("not configured") || lower.contains("unsupported provider")) {
            return "LLM_PROVIDER_NOT_CONFIGURED";
        }
        return "LLM_CALL_FAILED";
    }

    private String metricSummary(Map<String, Object> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        appendMetric(parts, metrics, "caseCount");
        appendMetric(parts, metrics, "completed");
        appendMetric(parts, metrics, "failed");
        appendMetric(parts, metrics, "skipped");
        appendMetric(parts, metrics, "beforeIssueCount");
        appendMetric(parts, metrics, "afterIssueCount");
        appendMetric(parts, metrics, "filteredByPriorFeedbackCount");
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private void appendMetric(List<String> parts, Map<String, Object> metrics, String name) {
        Object value = metrics.get(name);
        if (value instanceof Number || value instanceof Boolean) {
            parts.add(name + "=" + value);
        }
    }

    private Object sanitizeMetadataValue(Object value) {
        if (value instanceof String text) {
            return sanitizer.metadataText(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sanitized.put(
                        sanitizer.metadataText(String.valueOf(entry.getKey())),
                        sanitizeMetadataValue(entry.getValue())
                );
            }
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : iterable) {
                sanitized.add(sanitizeMetadataValue(item));
            }
            return sanitized;
        }
        return value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record ReviewContext(
            Long projectId,
            Long runId,
            String provider,
            String modelName
    ) {
    }
}
