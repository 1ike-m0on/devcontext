package com.devcontext.application.review;

import com.devcontext.domain.context.ContextItem;
import com.devcontext.domain.git.GitDiff;
import com.devcontext.domain.project.Project;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ReviewPromptBuilder {

    public String build(Project project, GitDiff diff, List<ContextItem> contextItems, String mode) {
        return """
                You are a senior Java backend code reviewer.

                Task:
                Review the current Git diff using the project context, review rules, coding preferences, and pitfalls.

                Project:
                - name: %s
                - path: %s
                - language: %s
                - framework: %s
                - review mode: %s

                Changed files:
                %s

                Context:
                %s

                Output strict JSON only, without Markdown fences.
                Use this schema:
                {
                  "score": 4.2,
                  "summary": "short review summary",
                  "changeIntent": "what this diff appears to change",
                  "impactScope": "affected modules and behavior",
                  "testGaps": ["missing test or validation"],
                  "recommendations": ["next action"],
                  "issues": [
                    {
                      "severity": "critical|warning|info",
                      "title": "short issue title",
                      "filePath": "path/from/repo",
                      "lineNumber": 12,
                      "description": "evidence-based issue",
                      "impact": "why it matters",
                      "suggestion": "specific fix suggestion",
                      "confidence": "high|medium|low"
                    }
                  ]
                }

                Rules:
                - Focus on the diff.
                - Do not invent problems without evidence.
                - Mark uncertain items as confidence low.
                - If there are no issues, return an empty issues array and a useful summary.
                - Keep suggestions concise.
                """.formatted(
                project.name(),
                project.rootPath(),
                project.language(),
                project.framework(),
                mode == null || mode.isBlank() ? "DEFAULT" : mode,
                diff.changedFiles().isEmpty() ? "No changed files listed." : String.join(System.lineSeparator(), diff.changedFiles()),
                renderContext(contextItems)
        );
    }

    private String renderContext(List<ContextItem> contextItems) {
        if (contextItems.isEmpty()) {
            return "Project context is unavailable.";
        }
        return contextItems.stream()
                .map(item -> """
                        --- %s | %s | priority=%d | source=%s ---
                        %s
                        """.formatted(item.type(), item.title(), item.priority(), item.source(), item.content()))
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
