package com.devcontext.application.review;

import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.application.review.context.ReviewContextAssembler;
import com.devcontext.application.review.context.ReviewContextRequest;
import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.common.error.ApiException;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.context.ContextItem;
import com.devcontext.domain.git.GitDiff;
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
import java.util.List;
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
    private final ReviewContextAssembler contextAssembler;
    private final ReviewPromptBuilder promptBuilder;
    private final ReviewReportParser reportParser;
    private final LlmClient llmClient;
    private final DevContextLlmProperties llmProperties;
    private final AgentRunApplicationService runService;
    private final ReviewRecordRepository reviewRecordRepository;
    private final ReviewIssueRepository reviewIssueRepository;
    private final ReviewReportStore reviewReportStore;

    public ReviewApplicationService(
            ProjectApplicationService projectService,
            GitDiffProvider gitDiffProvider,
            ReviewContextAssembler contextAssembler,
            ReviewPromptBuilder promptBuilder,
            ReviewReportParser reportParser,
            LlmClient llmClient,
            DevContextLlmProperties llmProperties,
            AgentRunApplicationService runService,
            ReviewRecordRepository reviewRecordRepository,
            ReviewIssueRepository reviewIssueRepository,
            ReviewReportStore reviewReportStore
    ) {
        this.projectService = projectService;
        this.gitDiffProvider = gitDiffProvider;
        this.contextAssembler = contextAssembler;
        this.promptBuilder = promptBuilder;
        this.reportParser = reportParser;
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.runService = runService;
        this.reviewRecordRepository = reviewRecordRepository;
        this.reviewIssueRepository = reviewIssueRepository;
        this.reviewReportStore = reviewReportStore;
    }

    public ReviewCreateResult createReview(Long projectId, CreateReviewCommand command) {
        Project project = projectService.getProject(projectId);
        AgentRun run = runService.startRun(projectId, "AI_CODE_REVIEW", llmProperties.modelName(), "mvp2");
        try {
            GitDiff diff = resolveDiff(project, command);
            if (diff.text() == null || diff.text().isBlank()) {
                throw new ApiException("REVIEW_DIFF_EMPTY", "Git diff is empty", HttpStatus.BAD_REQUEST);
            }
            runService.recordEvent(run.id(), "GIT_DIFF_COLLECTED", branchSummary(command), diff.changedFiles().size() + " changed files", "success", null, null);

            List<ContextItem> contextItems = contextAssembler.assemble(new ReviewContextRequest(project, diff));
            runService.recordEvent(run.id(), "PROJECT_CONTEXT_LOADED", "review context providers", contextItems.size() + " context items", "success", null, null);
            runService.recordEvent(run.id(), "DECISION_MEMORY_RECALLED", "mvp2", "Decision memory not implemented yet", "success", null, null);

            String prompt = promptBuilder.build(project, diff, contextItems, command.mode());
            runService.recordEvent(run.id(), "PROMPT_BUILT", "review prompt", prompt.length() + " chars", "success", null, null);

            LlmResponse response = llmClient.chat(new LlmRequest(prompt, llmProperties.modelName()));
            runService.recordEvent(run.id(), "LLM_CALLED", llmProperties.modelName(), "LLM response generated", "success", null, null);

            ParsedReviewReport report = reportParser.parse(response.content());
            runService.recordEvent(run.id(), "LLM_RESPONSE_PARSED", "model response", report.issues().size() + " issues parsed", "success", null, null);

            ReviewRecord saved = reviewRecordRepository.save(new ReviewRecord(
                    null,
                    projectId,
                    run.id(),
                    valueOr(command.baseBranch(), "provided"),
                    valueOr(command.compareBranch(), "provided"),
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
            return new ReviewCreateResult(updated.id(), run.id(), updated.score(), updated.summary(), updated.reportPath());
        } catch (RuntimeException e) {
            runService.failRun(run, e.getMessage());
            throw e;
        }
    }

    public ReviewDetail getReview(Long reviewId) {
        ReviewRecord record = reviewRecordRepository.findById(reviewId)
                .orElseThrow(() -> new ApiException("REVIEW_NOT_FOUND", "Review not found", HttpStatus.NOT_FOUND));
        return new ReviewDetail(record, reviewIssueRepository.findByReviewId(reviewId));
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
        reviewIssueRepository.findById(issueId)
                .orElseThrow(() -> new ApiException("REVIEW_ISSUE_NOT_FOUND", "Review issue not found", HttpStatus.NOT_FOUND));
        return reviewIssueRepository.updateStatus(issueId, normalized, note);
    }

    private GitDiff resolveDiff(Project project, CreateReviewCommand command) {
        if (command.diffText() != null && !command.diffText().isBlank()) {
            String diffText = command.diffText();
            return new GitDiff(diffText, changedFilesFromDiff(diffText), sha256(diffText), false);
        }
        if (command.baseBranch() == null || command.baseBranch().isBlank()
                || command.compareBranch() == null || command.compareBranch().isBlank()) {
            throw new ApiException("REVIEW_BRANCH_REQUIRED", "baseBranch and compareBranch are required when diffText is not provided", HttpStatus.BAD_REQUEST);
        }
        return gitDiffProvider.diff(project.rootPath(), command.baseBranch(), command.compareBranch());
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

    private String branchSummary(CreateReviewCommand command) {
        return valueOr(command.baseBranch(), "provided") + "..." + valueOr(command.compareBranch(), "provided");
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
