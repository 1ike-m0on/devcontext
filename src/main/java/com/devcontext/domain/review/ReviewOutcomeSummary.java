package com.devcontext.domain.review;

import java.util.List;

public record ReviewOutcomeSummary(
        int total,
        int pending,
        int accepted,
        int fixed,
        int falsePositive,
        int rejected,
        int ignored,
        int positiveOutcome,
        int negativeOutcome
) {
    public static ReviewOutcomeSummary empty() {
        return from(List.of());
    }

    public static ReviewOutcomeSummary from(List<ReviewIssue> issues) {
        List<ReviewIssue> safeIssues = issues == null ? List.of() : issues;
        int pending = 0;
        int accepted = 0;
        int fixed = 0;
        int falsePositive = 0;
        int rejected = 0;
        int ignored = 0;

        for (ReviewIssue issue : safeIssues) {
            String status = issue.status() == null ? "" : issue.status().trim().toLowerCase();
            switch (status) {
                case "pending" -> pending++;
                case "accepted" -> accepted++;
                case "fixed" -> fixed++;
                case "false_positive" -> falsePositive++;
                case "rejected" -> rejected++;
                case "ignored" -> ignored++;
                default -> {
                }
            }
        }
        int positiveOutcome = accepted + fixed;
        int negativeOutcome = falsePositive + rejected + ignored;
        return new ReviewOutcomeSummary(
                safeIssues.size(),
                pending,
                accepted,
                fixed,
                falsePositive,
                rejected,
                ignored,
                positiveOutcome,
                negativeOutcome
        );
    }
}
