package com.devcontext.application.review;

import com.devcontext.application.decision.DecisionSearchCommand;
import com.devcontext.application.decision.DecisionSearchService;
import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.application.review.context.ReviewContextAssembler;
import com.devcontext.application.review.context.ReviewContextRequest;
import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.common.error.ApiException;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.context.ContextItem;
import com.devcontext.domain.decision.DecisionCard;
import com.devcontext.domain.decision.DecisionEvidence;
import com.devcontext.domain.decision.DecisionSearchResult;
import com.devcontext.domain.git.GitDiff;
import com.devcontext.domain.git.GitReviewSource;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.domain.project.Project;
import com.devcontext.domain.review.ParsedReviewReport;
import com.devcontext.domain.review.ReviewCreateResult;
import com.devcontext.domain.review.ReviewDetail;
import com.devcontext.domain.review.ReviewEventDetail;
import com.devcontext.domain.review.ReviewIssue;
import com.devcontext.domain.review.ReviewIssueDraft;
import com.devcontext.domain.review.ReviewRecord;
import com.devcontext.domain.run.AgentRun;
import com.devcontext.ports.git.GitDiffProvider;
import com.devcontext.ports.llm.LlmClient;
import com.devcontext.ports.review.ReviewIssueRepository;
import com.devcontext.ports.review.ReviewRecordRepository;
import com.devcontext.ports.review.ReviewReportStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ReviewApplicationService {

    private static final Set<String> VALID_ISSUE_STATUSES = Set.of(
            "pending", "accepted", "rejected", "false_positive", "fixed", "ignored"
    );

    private final ProjectApplicationService projectService;
    private final GitDiffProvider gitDiffProvider;
    private final DecisionSearchService decisionSearchService;
    private final ReviewContextAssembler contextAssembler;
    private final ReviewPromptBuilder promptBuilder;
    private final ReviewReportParser reportParser;
    private final ReviewReportPostProcessor reportPostProcessor;
    private final LlmClient llmClient;
    private final DevContextLlmProperties llmProperties;
    private final AgentRunApplicationService runService;
    private final ReviewRecordRepository reviewRecordRepository;
    private final ReviewIssueRepository reviewIssueRepository;
    private final ReviewReportStore reviewReportStore;

    public ReviewApplicationService(
            ProjectApplicationService projectService,
            GitDiffProvider gitDiffProvider,
            DecisionSearchService decisionSearchService,
            ReviewContextAssembler contextAssembler,
            ReviewPromptBuilder promptBuilder,
            ReviewReportParser reportParser,
            ReviewReportPostProcessor reportPostProcessor,
            LlmClient llmClient,
            DevContextLlmProperties llmProperties,
            AgentRunApplicationService runService,
            ReviewRecordRepository reviewRecordRepository,
            ReviewIssueRepository reviewIssueRepository,
            ReviewReportStore reviewReportStore
    ) {
        this.projectService = projectService;
        this.gitDiffProvider = gitDiffProvider;
        this.decisionSearchService = decisionSearchService;
        this.contextAssembler = contextAssembler;
        this.promptBuilder = promptBuilder;
        this.reportParser = reportParser;
        this.reportPostProcessor = reportPostProcessor;
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.runService = runService;
        this.reviewRecordRepository = reviewRecordRepository;
        this.reviewIssueRepository = reviewIssueRepository;
        this.reviewReportStore = reviewReportStore;
    }

    public ReviewCreateResult createReview(Long projectId, CreateReviewCommand command) {
        Project project = projectService.getProject(projectId);
        AgentRun run = runService.startRun(projectId, "AI_CODE_REVIEW", "mvp2");
        try {
            GitDiff diff = resolveDiff(project, command);
            if (diff.text() == null || diff.text().isBlank()) {
                throw new ApiException("REVIEW_DIFF_EMPTY", "Git diff is empty", HttpStatus.BAD_REQUEST);
            }
            runService.recordEvent(run.id(), "GIT_DIFF_COLLECTED", diffSummary(diff), diffStatusSummary(diff), "success", null, null);
            if (diff.truncated()) {
                runService.recordEvent(run.id(), "GIT_DIFF_TRUNCATED", diffSummary(diff), "Diff exceeded DevContext review context limit; only the first chunk was sent to the LLM.", "success", null, null);
            }

            List<ContextItem> contextItems = new ArrayList<>(contextAssembler.assemble(new ReviewContextRequest(project, diff)));
            runService.recordEvent(run.id(), "PROJECT_CONTEXT_LOADED", "review context providers", contextItems.size() + " context items", "success", null, null);
            List<DecisionSearchResult> decisionMatches = recallReviewDecisions(project, diff);
            runService.recordEvent(run.id(), "DECISION_MEMORY_RECALLED", reviewDecisionQuerySummary(diff), decisionMatches.size() + " decision cards recalled", "success", null, null);
            decisionMemoryContext(project, decisionMatches).ifPresent(contextItems::add);
            List<ReviewIssue> reviewFeedback = reviewIssueRepository.findRecentFeedbackByProjectId(project.id(), 8);
            Optional<ContextItem> reviewFeedbackItem = reviewFeedbackContext(project, reviewFeedback);
            if (reviewFeedbackItem.isPresent()) {
                contextItems.add(reviewFeedbackItem.get());
                runService.recordEvent(
                        run.id(),
                        "REVIEW_FEEDBACK_MEMORY_LOADED",
                        "project " + project.id(),
                        reviewFeedbackSummary(reviewFeedback),
                        "success",
                        null,
                        null
                );
            }
            contextItems = contextItems.stream()
                    .sorted(Comparator.comparingInt(ContextItem::priority).reversed())
                    .toList();

            String prompt = promptBuilder.build(project, diff, contextItems, command.mode());
            runService.recordEvent(run.id(), "PROMPT_BUILT", "review prompt", prompt.length() + " chars", "success", null, null);

            runService.recordEvent(run.id(), "LLM_CALL_STARTED", llmProperties.providerModelLabel(), "Sending review prompt to LLM", "success", null, null);
            LlmResponse response = llmClient.chat(new LlmRequest(prompt, llmProperties.modelName()));
            runService.recordEvent(run.id(), "LLM_CALLED", llmProperties.providerModelLabel(), "LLM response generated", "success", null, null);

            ParsedReviewReport rawReport = reportParser.parse(response.content());
            runService.recordEvent(run.id(), "LLM_RESPONSE_PARSED", "model response", rawReport.issues().size() + " issues parsed", "success", null, null);

            ReviewReportPostProcessor.ProcessedReviewReport processedReport = reportPostProcessor.process(rawReport, diff, reviewFeedback);
            ParsedReviewReport report = processedReport.report();
            runService.recordEvent(
                    run.id(),
                    "REVIEW_ISSUES_FILTERED",
                    rawReport.issues().size() + " parsed issues",
                    reviewIssueFilterSummary(report, processedReport),
                    "success",
                    null,
                    null
            );

            ReviewRecord saved = reviewRecordRepository.save(new ReviewRecord(
                    null,
                    projectId,
                    run.id(),
                    valueOr(diff.baseRef(), valueOr(command.baseBranch(), "provided")),
                    valueOr(diff.compareRef(), valueOr(command.compareBranch(), "provided")),
                    diff.hash(),
                    report.score(),
                    report.summary(),
                    null,
                    Instant.now()
            ));
            List<ReviewIssue> issues = saveIssues(saved.id(), report.issues());
            runService.recordEvent(run.id(), "REVIEW_ISSUES_SAVED", "parsed issues", issues.size() + " issues saved", "success", null, null);

            String reportPath = reviewReportStore.writeReport(project.rootPath(), saved.id(), report.markdown());
            ReviewRecord updated = reviewRecordRepository.updateReportPath(saved.id(), reportPath);

            runService.finishRun(run, response.inputTokenEstimate(), response.outputTokenEstimate());
            return new ReviewCreateResult(updated.id(), run.id(), updated.score(), updated.summary(), updated.reportPath(), diff.truncated());
        } catch (RuntimeException e) {
            runService.failRun(run, e.getMessage());
            throw e;
        }
    }

    public List<GitReviewSource> inspectReviewSources(Long projectId) {
        Project project = projectService.getProject(projectId);
        return gitDiffProvider.inspectSources(project.rootPath(), project.defaultBranch());
    }

    public ReviewDetail getReview(Long reviewId) {
        ReviewRecord record = reviewRecordRepository.findById(reviewId)
                .orElseThrow(() -> new ApiException("REVIEW_NOT_FOUND", "Review not found", HttpStatus.NOT_FOUND));
        return new ReviewDetail(record, reviewIssueRepository.findByReviewId(reviewId));
    }

    public List<ReviewRecord> listProjectReviews(Long projectId, int limit) {
        return reviewRecordRepository.findByProjectId(projectId, limit);
    }

    public ReviewEventDetail getReviewEvents(Long reviewId) {
        ReviewRecord record = reviewRecordRepository.findById(reviewId)
                .orElseThrow(() -> new ApiException("REVIEW_NOT_FOUND", "Review not found", HttpStatus.NOT_FOUND));
        return new ReviewEventDetail(record.id(), record.runId(), runService.listEvents(record.runId()));
    }

    public ReviewIssue updateIssueStatus(Long issueId, String status, String note) {
        String normalized = status == null ? "" : status.trim().toLowerCase();
        if (!VALID_ISSUE_STATUSES.contains(normalized)) {
            throw new ApiException("REVIEW_ISSUE_STATUS_INVALID", "Invalid review issue status", HttpStatus.BAD_REQUEST);
        }
        ReviewIssue existing = reviewIssueRepository.findById(issueId)
                .orElseThrow(() -> new ApiException("REVIEW_ISSUE_NOT_FOUND", "Review issue not found", HttpStatus.NOT_FOUND));
        ReviewIssue updated = reviewIssueRepository.updateStatus(issueId, normalized, note);
        ReviewRecord review = reviewRecordRepository.findById(existing.reviewId())
                .orElseThrow(() -> new ApiException("REVIEW_NOT_FOUND", "Review not found", HttpStatus.NOT_FOUND));
        runService.recordEvent(
                review.runId(),
                "REVIEW_ISSUE_STATUS_UPDATED",
                "issue " + issueId + ": " + existing.status() + " -> " + normalized,
                feedbackSummary(updated, note),
                "success",
                null,
                null
        );
        return updated;
    }

    private String feedbackSummary(ReviewIssue issue, String note) {
        String summary = issue.title() + " [" + issue.status() + "]";
        if (note == null || note.isBlank()) {
            return summary;
        }
        return summary + " - " + note.trim();
    }

    private List<DecisionSearchResult> recallReviewDecisions(Project project, GitDiff diff) {
        String query = reviewDecisionQuery(diff);
        if (query.isBlank()) {
            return List.of();
        }
        return decisionSearchService.search(new DecisionSearchCommand(
                query,
                project.id(),
                List.of(),
                3
        )).matches();
    }

    private Optional<ContextItem> decisionMemoryContext(Project project, List<DecisionSearchResult> matches) {
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        String content = renderDecisionMemoryContext(matches);
        return Optional.of(new ContextItem(
                null,
                null,
                project.id(),
                "DECISION_MEMORY",
                "Relevant Decision Cards",
                content,
                "decision-memory:review-recall",
                880,
                estimateTokens(content),
                sha256(content),
                Instant.now()
        ));
    }

    private String renderDecisionMemoryContext(List<DecisionSearchResult> matches) {
        StringBuilder builder = new StringBuilder();
        builder.append("Relevant engineering decisions recalled for this code review.")
                .append(System.lineSeparator());
        builder.append("Use them as project memory. Do not blindly reuse; compare applicability with the current diff.")
                .append(System.lineSeparator())
                .append(System.lineSeparator());
        for (DecisionSearchResult match : matches) {
            DecisionCard decision = match.decision();
            builder.append("- Decision #").append(decision.id()).append(": ").append(valueOr(decision.title(), "Untitled"))
                    .append(" (score=").append(match.score()).append(")")
                    .append(System.lineSeparator());
            appendLine(builder, "  Scenario", decision.scenario());
            appendLine(builder, "  Decision", decision.decision());
            appendList(builder, "  Applicable when", decision.applicableWhen());
            appendList(builder, "  Not applicable when", decision.notApplicableWhen());
            appendList(builder, "  Trade-offs", decision.tradeOffs());
            appendEvidence(builder, decision.evidence());
            builder.append(System.lineSeparator());
        }
        return builder.toString();
    }

    private Optional<ContextItem> reviewFeedbackContext(Project project, List<ReviewIssue> feedback) {
        if (feedback.isEmpty()) {
            return Optional.empty();
        }
        String content = renderReviewFeedbackContext(feedback);
        return Optional.of(new ContextItem(
                null,
                null,
                project.id(),
                "REVIEW_FEEDBACK_MEMORY",
                "Recent Human Review Feedback",
                content,
                "review-feedback:project-history",
                870,
                estimateTokens(content),
                sha256(content),
                Instant.now()
        ));
    }

    private String renderReviewFeedbackContext(List<ReviewIssue> feedback) {
        StringBuilder builder = new StringBuilder();
        builder.append("Recent human feedback for this project's code reviews.")
                .append(System.lineSeparator());
        builder.append("Use this as quality calibration, not as automatic truth.")
                .append(System.lineSeparator());
        builder.append("- accepted/fixed: trusted patterns when the current diff has matching evidence.")
                .append(System.lineSeparator());
        builder.append("- false_positive/rejected: noise patterns to avoid unless the current diff has stronger direct evidence.")
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        List<ReviewIssue> trusted = feedback.stream()
                .filter(issue -> isTrustedReviewFeedback(issue.status()))
                .toList();
        List<ReviewIssue> noise = feedback.stream()
                .filter(issue -> isNoiseReviewFeedback(issue.status()))
                .toList();
        appendFeedbackSection(builder, "Trusted patterns", trusted);
        appendFeedbackSection(builder, "Noise patterns", noise);
        return builder.toString();
    }

    private void appendFeedbackSection(StringBuilder builder, String title, List<ReviewIssue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        builder.append(title).append(":").append(System.lineSeparator());
        for (ReviewIssue issue : issues) {
            builder.append("- [").append(valueOr(issue.status(), "unknown")).append("] ")
                    .append(trimForPrompt(valueOr(issue.title(), "Untitled issue"), 140));
            if (issue.filePath() != null && !issue.filePath().isBlank()) {
                builder.append(" | ").append(issue.filePath());
                if (issue.lineNumber() != null) {
                    builder.append(":").append(issue.lineNumber());
                }
            }
            builder.append(System.lineSeparator());
            appendLine(builder, "  Description", trimForPrompt(issue.description(), 260));
            appendLine(builder, "  Human note", trimForPrompt(issue.note(), 220));
        }
        builder.append(System.lineSeparator());
    }

    private String reviewFeedbackSummary(List<ReviewIssue> feedback) {
        long trusted = feedback.stream().filter(issue -> isTrustedReviewFeedback(issue.status())).count();
        long noise = feedback.stream().filter(issue -> isNoiseReviewFeedback(issue.status())).count();
        return trusted + " trusted patterns, " + noise + " noise patterns";
    }

    private String reviewIssueFilterSummary(
            ParsedReviewReport report,
            ReviewReportPostProcessor.ProcessedReviewReport processedReport
    ) {
        String summary = report.issues().size() + " issues retained, "
                + processedReport.downgradedIssueCount() + " downgraded";
        if (processedReport.feedbackDowngradedIssueCount() > 0) {
            return summary + ", " + processedReport.feedbackDowngradedIssueCount() + " by prior feedback";
        }
        return summary;
    }

    private boolean isTrustedReviewFeedback(String status) {
        return "accepted".equals(status) || "fixed".equals(status);
    }

    private boolean isNoiseReviewFeedback(String status) {
        return "false_positive".equals(status) || "rejected".equals(status);
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(label).append(": ").append(value.trim()).append(System.lineSeparator());
        }
    }

    private void appendList(StringBuilder builder, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append(label).append(": ").append(String.join("; ", values)).append(System.lineSeparator());
    }

    private void appendEvidence(StringBuilder builder, List<DecisionEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return;
        }
        List<String> summaries = evidence.stream()
                .map(item -> valueOr(item.type(), "evidence") + ":" + valueOr(item.ref(), "manual") + " - " + valueOr(item.summary(), ""))
                .toList();
        builder.append("  Evidence: ").append(String.join("; ", summaries)).append(System.lineSeparator());
    }

    private String reviewDecisionQuery(GitDiff diff) {
        StringBuilder builder = new StringBuilder();
        builder.append(valueOr(diff.sourceType(), "review")).append(' ');
        builder.append(String.join(" ", diff.changedFiles())).append(' ');
        if (diff.text() != null && !diff.text().isBlank()) {
            int length = Math.min(diff.text().length(), 8_000);
            builder.append(diff.text(), 0, length);
        }
        return builder.toString().trim();
    }

    private String reviewDecisionQuerySummary(GitDiff diff) {
        if (diff.changedFiles().isEmpty()) {
            return valueOr(diff.sourceType(), "review");
        }
        return valueOr(diff.sourceType(), "review") + ": " + String.join(", ", diff.changedFiles().stream().limit(5).toList());
    }

    private GitDiff resolveDiff(Project project, CreateReviewCommand command) {
        if (command.diffText() != null && !command.diffText().isBlank()) {
            String diffText = command.diffText();
            return new GitDiff(diffText, changedFilesFromDiff(diffText), sha256(diffText), false, "manual", "provided", "manual_diff");
        }
        String sourceType = command.sourceType() == null ? "" : command.sourceType().trim().toLowerCase();
        if (!sourceType.isBlank() && !"branch".equals(sourceType) && !"manual".equals(sourceType)) {
            return resolveReviewSource(project, sourceType, command.selectedFiles());
        }
        if (command.baseBranch() == null || command.baseBranch().isBlank()
                || command.compareBranch() == null || command.compareBranch().isBlank()) {
            return resolveReviewSource(project, "auto", command.selectedFiles());
        }
        return gitDiffProvider.diff(project.rootPath(), command.baseBranch(), command.compareBranch(), command.selectedFiles());
    }

    private GitDiff resolveReviewSource(Project project, String sourceType, List<String> selectedFiles) {
        return switch (sourceType) {
            case "working_tree" -> gitDiffProvider.workingTreeDiff(project.rootPath(), selectedFiles);
            case "current_branch" -> gitDiffProvider.currentBranchDiff(project.rootPath(), project.defaultBranch(), selectedFiles);
            case "last_commit" -> gitDiffProvider.lastCommitDiff(project.rootPath(), selectedFiles);
            case "auto", "" -> resolveAutoReviewSource(project, selectedFiles);
            default -> throw new ApiException("REVIEW_SOURCE_INVALID", "Invalid review source type", HttpStatus.BAD_REQUEST);
        };
    }

    private GitDiff resolveAutoReviewSource(Project project, List<String> selectedFiles) {
        List<GitReviewSource> sources = gitDiffProvider.inspectSources(project.rootPath(), project.defaultBranch());
        return sources.stream()
                .filter(GitReviewSource::available)
                .filter(GitReviewSource::recommended)
                .findFirst()
                .or(() -> sources.stream().filter(GitReviewSource::available).findFirst())
                .map(source -> resolveReviewSource(project, source.sourceType(), selectedFiles))
                .orElseThrow(() -> new ApiException("REVIEW_DIFF_EMPTY", "No reviewable Git changes found", HttpStatus.BAD_REQUEST));
    }

    private List<ReviewIssue> saveIssues(Long reviewId, List<ReviewIssueDraft> drafts) {
        Instant now = Instant.now();
        return drafts.stream()
                .map(draft -> reviewIssueRepository.save(new ReviewIssue(
                        null,
                        reviewId,
                        draft.severity(),
                        draft.title(),
                        draft.filePath(),
                        draft.lineNumber(),
                        draft.description(),
                        draft.impact(),
                        draft.suggestion(),
                        draft.confidence(),
                        "pending",
                        null,
                        now,
                        now
                )))
                .toList();
    }

    private List<String> changedFilesFromDiff(String diffText) {
        return diffText.lines()
                .filter(line -> line.startsWith("diff --git "))
                .map(line -> {
                    int marker = line.indexOf(" b/");
                    return marker >= 0 ? line.substring(marker + 3).trim() : line;
                })
                .distinct()
                .toList();
    }

    private String diffSummary(GitDiff diff) {
        return valueOr(diff.sourceType(), "manual") + ": " + valueOr(diff.baseRef(), "provided") + "..." + valueOr(diff.compareRef(), "provided");
    }

    private String diffStatusSummary(GitDiff diff) {
        String summary = diff.changedFiles().size() + " changed files";
        if (diff.truncated()) {
            return summary + ", diff truncated";
        }
        return summary;
    }

    private int estimateTokens(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return Math.max(1, content.length() / 4);
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String trimForPrompt(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength - 3) + "...";
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
