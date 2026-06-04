package com.devcontext.application.review;

import com.devcontext.domain.review.ParsedReviewReport;
import com.devcontext.domain.review.ReviewIssueDraft;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReviewReportParser {

    private final ObjectMapper objectMapper;

    public ReviewReportParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedReviewReport parse(String responseContent) {
        String json = extractJson(responseContent);
        if (json != null) {
            try {
                JsonNode root = objectMapper.readTree(json);
                List<String> testGaps = readStringArray(root.path("testGaps"));
                List<String> recommendations = readStringArray(root.path("recommendations"));
                List<ReviewIssueDraft> issues = readIssues(root.path("issues"));
                String summary = text(root, "summary", "AI review completed.");
                String changeIntent = text(root, "changeIntent", "Need confirmation.");
                String impactScope = text(root, "impactScope", "Need confirmation.");
                double score = root.path("score").asDouble(3.0);
                String markdown = renderMarkdown(score, summary, changeIntent, impactScope, testGaps, recommendations, issues, responseContent);
                return new ParsedReviewReport(score, summary, changeIntent, impactScope, testGaps, recommendations, issues, markdown, responseContent);
            } catch (IOException ignored) {
                // Fall through to raw response handling.
            }
        }
        String summary = "AI review response captured, but structured JSON parsing was not available.";
        String markdown = """
                # AI Code Review Report

                ## Summary

                %s

                ## Raw Response

                %s
                """.formatted(summary, responseContent == null ? "" : responseContent);
        return new ParsedReviewReport(3.0, summary, "Need confirmation.", "Need confirmation.", List.of(), List.of(), List.of(), markdown, responseContent);
    }

    public ParsedReviewReport rebuild(
            ParsedReviewReport report,
            List<String> testGaps,
            List<String> recommendations,
            List<ReviewIssueDraft> issues
    ) {
        String markdown = renderMarkdown(
                report.score(),
                report.summary(),
                report.changeIntent(),
                report.impactScope(),
                testGaps,
                recommendations,
                issues,
                report.rawResponse()
        );
        return new ParsedReviewReport(
                report.score(),
                report.summary(),
                report.changeIntent(),
                report.impactScope(),
                testGaps,
                recommendations,
                issues,
                markdown,
                report.rawResponse()
        );
    }

    private String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return trimmed.substring(start, end + 1);
    }

    private List<String> readStringArray(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private List<ReviewIssueDraft> readIssues(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<ReviewIssueDraft> issues = new ArrayList<>();
        for (JsonNode item : node) {
            issues.add(new ReviewIssueDraft(
                    normalizeSeverity(text(item, "severity", "info")),
                    text(item, "title", "Untitled issue"),
                    text(item, "filePath", null),
                    item.path("lineNumber").isNumber() ? item.path("lineNumber").asInt() : null,
                    text(item, "description", ""),
                    text(item, "impact", ""),
                    text(item, "suggestion", ""),
                    text(item, "confidence", "medium")
            ));
        }
        return issues;
    }

    private String renderMarkdown(
            double score,
            String summary,
            String changeIntent,
            String impactScope,
            List<String> testGaps,
            List<String> recommendations,
            List<ReviewIssueDraft> issues,
            String rawResponse
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("# AI Code Review Report\n\n");
        builder.append("## 1. Review Overview\n\n");
        builder.append("- Score: ").append(score).append("\n");
        builder.append("- Summary: ").append(summary).append("\n\n");
        builder.append("## 2. Change Intent\n\n").append(changeIntent).append("\n\n");
        builder.append("## 3. Impact Scope\n\n").append(impactScope).append("\n\n");
        appendIssues(builder, "critical", issues);
        appendIssues(builder, "warning", issues);
        appendIssues(builder, "info", issues);
        builder.append("## 7. Test Gaps\n\n");
        appendList(builder, testGaps);
        builder.append("\n## 8. Recommendations\n\n");
        appendList(builder, recommendations);
        builder.append("\n## Raw Model Response\n\n");
        builder.append("```json\n").append(rawResponse == null ? "" : rawResponse).append("\n```\n");
        return builder.toString();
    }

    private void appendIssues(StringBuilder builder, String severity, List<ReviewIssueDraft> issues) {
        String title = switch (severity) {
            case "critical" -> "## 4. Critical Issues";
            case "warning" -> "## 5. Warning Issues";
            default -> "## 6. Info Suggestions";
        };
        builder.append(title).append("\n\n");
        List<ReviewIssueDraft> filtered = issues.stream()
                .filter(issue -> severity.equals(issue.severity()))
                .toList();
        if (filtered.isEmpty()) {
            builder.append("None.\n\n");
            return;
        }
        int index = 1;
        for (ReviewIssueDraft issue : filtered) {
            builder.append("### ").append(severity.charAt(0)).append("-").append(index++).append(" ").append(issue.title()).append("\n\n");
            builder.append("- File: ").append(nullToDash(issue.filePath())).append("\n");
            builder.append("- Line: ").append(issue.lineNumber() == null ? "-" : issue.lineNumber()).append("\n");
            builder.append("- Description: ").append(nullToDash(issue.description())).append("\n");
            builder.append("- Impact: ").append(nullToDash(issue.impact())).append("\n");
            builder.append("- Suggestion: ").append(nullToDash(issue.suggestion())).append("\n");
            builder.append("- Confidence: ").append(nullToDash(issue.confidence())).append("\n\n");
        }
    }

    private void appendList(StringBuilder builder, List<String> values) {
        if (values.isEmpty()) {
            builder.append("- None.\n");
            return;
        }
        values.forEach(value -> builder.append("- ").append(value).append("\n"));
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }

    private String normalizeSeverity(String severity) {
        String value = severity == null ? "info" : severity.trim().toLowerCase();
        if (value.equals("critical") || value.equals("warning") || value.equals("info")) {
            return value;
        }
        return "info";
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
