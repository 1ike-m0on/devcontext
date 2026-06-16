package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.devcontext.application.llm.LlmErrorTypes;
import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.application.review.ReviewMemorySignalService;
import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.project.Project;
import com.devcontext.domain.review.ReviewIssue;
import com.devcontext.domain.review.ReviewMemorySignal;
import com.devcontext.domain.review.ReviewRecord;
import com.devcontext.domain.run.AgentEvent;
import com.devcontext.domain.run.AgentRun;
import com.devcontext.ports.review.ReviewIssueRepository;
import com.devcontext.ports.review.ReviewRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-real-llm-manual-acceptance.sqlite",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
class RealLlmManualAcceptance {

    private static final String REPORT_SCHEMA = "real-llm-manual-acceptance";
    private static final int REPORT_VERSION = 1;
    private static final String SUITE = "real-llm-manual-acceptance";
    private static final String MODE = "manual";
    private static final String CASE_NAME = "review-memory-false-positive-suppression";
    private static final String FAILURE_CATEGORY_NONE = "none";
    private static final String FAILURE_CATEGORY_UNKNOWN = "unknown";
    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Pattern FILTER_SUMMARY_PATTERN =
            Pattern.compile("(\\d+) issues retained, (\\d+) downgraded(?:, (\\d+) by prior feedback)?");

    static {
        try {
            Path db = Path.of("target/devcontext-real-llm-manual-acceptance.sqlite");
            Files.deleteIfExists(db);
            Files.deleteIfExists(Path.of(db + "-shm"));
            Files.deleteIfExists(Path.of(db + "-wal"));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private ProjectApplicationService projectService;

    @Autowired
    private AgentRunApplicationService runService;

    @Autowired
    private ReviewMemorySignalService reviewMemorySignalService;

    @Autowired
    private ReviewRecordRepository reviewRecordRepository;

    @Autowired
    private ReviewIssueRepository reviewIssueRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DevContextLlmProperties llmProperties;

    @TempDir
    private Path projectRoot;

    @Test
    void manualAcceptanceWritesUnifiedReport() throws Exception {
        createReviewFixture(projectRoot);
        Project project = projectService.createProject(
                "real-llm-manual-acceptance",
                projectRoot.toString(),
                "main"
        );

        ConnectionCheckResult connectionCheck = runConnectionCheck();
        ReviewMemorySmokeResult reviewMemorySmoke = runReviewMemorySmoke(project, connectionCheck);
        AcceptanceReport report = writeAcceptanceReport(project, connectionCheck, reviewMemorySmoke);

        assertAcceptanceReportContract(report, connectionCheck, reviewMemorySmoke);
        assertReportDoesNotExposeSecrets(report);
    }

    private ConnectionCheckResult runConnectionCheck() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/settings/llm/test"))
                .andReturn();
        JsonNode data = responseDataOrThrow(result);
        return new ConnectionCheckResult(
                data.path("provider").asText(provider()),
                data.path("model").asText(model()),
                data.path("timeout").asText(""),
                data.path("success").asBoolean(false),
                data.path("failureCategory").asText(FAILURE_CATEGORY_UNKNOWN),
                sanitizeMessage(data.path("messageSummary").asText("")),
                data.path("keyConfigured").asBoolean(false),
                data.path("keyStatus").asText("unknown")
        );
    }

    private ReviewMemorySmokeResult runReviewMemorySmoke(Project project, ConnectionCheckResult connectionCheck) throws Exception {
        if ("mock".equals(connectionCheck.provider())) {
            return skippedReviewMemorySmoke(
                    FAILURE_CATEGORY_NONE,
                    "Review memory real-provider smoke skipped because the active provider is mock."
            );
        }
        if (!connectionCheck.success()) {
            return skippedReviewMemorySmoke(
                    connectionCheck.failureCategory(),
                    "Review memory real-provider smoke skipped because connection check failed: "
                            + connectionCheck.messageSummary()
            );
        }
        if (!realProviderConfigured()) {
            return skippedReviewMemorySmoke(
                    LlmErrorTypes.PROVIDER_NOT_CONFIGURED,
                    "Review memory real-provider smoke skipped because the active real provider has no configured API key."
            );
        }

        String feedbackNote = "Manual acceptance: repository contract already guarantees a non-null user.";
        String diffText = "diff --git a/src/main/java/demo/UserService.java b/src/main/java/demo/UserService.java\n"
                + "+User user = userRepository.findById(id);\n"
                + "+return user.getName();";
        SeededPriorFeedback prior = seedPriorFalsePositiveFeedback(project, feedbackNote);

        try {
            JsonNode secondCreate = createReview(project.id(), "feature/manual-acceptance-later-review", diffText);
            long laterReviewId = secondCreate.path("reviewId").asLong();
            JsonNode secondDetail = reviewDetail(laterReviewId);
            JsonNode secondEvents = reviewEvents(laterReviewId);
            JsonNode signal = firstSignalOrThrow(secondCreate.path("reviewMemorySignals"));
            String feedbackLoadedSummary = eventOutput(secondEvents.path("events"), "REVIEW_FEEDBACK_MEMORY_LOADED");
            String coverageSummary = eventOutput(secondEvents.path("events"), "REVIEW_CONTEXT_COVERAGE_RECORDED");
            String filteredSummary = eventOutput(secondEvents.path("events"), "REVIEW_ISSUES_FILTERED");
            int afterIssueCount = secondDetail.path("issues").size();
            FilterCounts filterCounts = parseFilterCounts(filteredSummary, afterIssueCount);
            JsonNode contextCoverage = secondCreate.path("contextCoverage");

            if (!contextCoverage.path("reviewMemorySignals").asBoolean(false)) {
                throw new IllegalStateException("Review memory context coverage was not recorded as loaded.");
            }

            return completedReviewMemorySmoke(
                    prior,
                    laterReviewId,
                    secondCreate.path("runId").asLong(),
                    signal,
                    contextCoverage,
                    jsonTextList(contextCoverage.path("sourceTypes")),
                    feedbackLoadedSummary,
                    coverageSummary,
                    filteredSummary,
                    filterCounts,
                    secondDetail.path("outcomeSummary")
            );
        } catch (Exception e) {
            RunTrace trace = latestReviewRunTrace(project.id());
            return failedReviewMemorySmoke(
                    prior,
                    trace,
                    classifyFailure(e),
                    sanitizeMessage(e.getMessage())
            );
        }
    }

    private ReviewMemorySmokeResult skippedReviewMemorySmoke(String failureCategory, String messageSummary) {
        ReviewMemorySmokeResult result = new ReviewMemorySmokeResult();
        result.skipped = true;
        result.failureCategory = failureCategory;
        result.messageSummary = messageSummary;
        return result;
    }

    private ReviewMemorySmokeResult completedReviewMemorySmoke(
            SeededPriorFeedback prior,
            Long laterReviewId,
            Long reviewRunId,
            JsonNode memorySignal,
            JsonNode contextCoverage,
            List<String> sourceTypes,
            String feedbackLoadedSummary,
            String contextCoverageSummary,
            String reviewIssuesFilteredSummary,
            FilterCounts filterCounts,
            JsonNode laterOutcomeSummary
    ) {
        ReviewMemorySmokeResult result = new ReviewMemorySmokeResult();
        result.executed = true;
        result.success = true;
        result.priorReviewId = prior.reviewId();
        result.priorIssueId = prior.issueId();
        result.laterReviewId = laterReviewId;
        result.reviewRunId = reviewRunId;
        result.memorySignal = memorySignal;
        result.contextCoverage = contextCoverage;
        result.sourceTypes = sourceTypes;
        result.feedbackLoadedSummary = feedbackLoadedSummary;
        result.contextCoverageSummary = contextCoverageSummary;
        result.reviewIssuesFilteredSummary = reviewIssuesFilteredSummary;
        result.priorFeedbackMemoryLoaded = !feedbackLoadedSummary.isBlank();
        result.filteredByPriorFeedback = filterCounts.filteredByPriorFeedback();
        result.filteredByPriorFeedbackCount = filterCounts.filteredByPriorFeedbackCount();
        result.beforeIssueCount = filterCounts.beforeIssueCount();
        result.afterIssueCount = filterCounts.afterIssueCount();
        result.laterOutcomeSummary = laterOutcomeSummary;
        result.messageSummary = "Review memory real-provider smoke completed.";
        return result;
    }

    private ReviewMemorySmokeResult failedReviewMemorySmoke(
            SeededPriorFeedback prior,
            RunTrace trace,
            String failureCategory,
            String messageSummary
    ) {
        ReviewMemorySmokeResult result = new ReviewMemorySmokeResult();
        result.executed = true;
        result.success = false;
        result.failureCategory = failureCategory;
        result.messageSummary = messageSummary;
        result.priorReviewId = prior.reviewId();
        result.priorIssueId = prior.issueId();
        result.memorySignal = prior.memorySignal();
        result.reviewRunId = trace.runId();
        result.feedbackLoadedSummary = trace.feedbackLoadedSummary();
        result.contextCoverageSummary = trace.contextCoverageSummary();
        result.reviewIssuesFilteredSummary = trace.filteredSummary();
        result.contextCoverage = contextCoverageFromTrace(trace);
        result.sourceTypes = jsonTextList(result.contextCoverage.path("sourceTypes"));
        result.priorFeedbackMemoryLoaded = !trace.feedbackLoadedSummary().isBlank();
        return result;
    }

    private AcceptanceReport writeAcceptanceReport(
            Project project,
            ConnectionCheckResult connectionCheck,
            ReviewMemorySmokeResult reviewMemorySmoke
    ) throws IOException {
        Instant generatedAt = Instant.now();
        String runId = "real-llm-manual-acceptance-" + RUN_ID_FORMAT.format(generatedAt);
        Path reportDir = Path.of("target", "real-llm-manual-acceptance");
        Files.createDirectories(reportDir);
        Path jsonPath = reportDir.resolve(runId + ".json");
        Path markdownPath = reportDir.resolve(runId + ".md");
        String topLevelFailureCategory = topLevelFailureCategory(connectionCheck, reviewMemorySmoke);
        boolean success = connectionCheck.success() && reviewMemorySmoke.success();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", REPORT_SCHEMA);
        payload.put("version", REPORT_VERSION);
        payload.put("runId", runId);
        payload.put("generatedAt", generatedAt.toString());
        payload.put("suite", SUITE);
        payload.put("mode", MODE);
        payload.put("provider", connectionCheck.provider());
        payload.put("model", connectionCheck.model());
        payload.put("timeout", connectionCheck.timeout());
        payload.put("keyStatus", connectionCheck.keyStatus());
        payload.put("keyConfigured", connectionCheck.keyConfigured());
        payload.put("success", success);
        payload.put("status", acceptanceStatus(connectionCheck, reviewMemorySmoke, success));
        payload.put("failureCategory", topLevelFailureCategory);
        payload.put("messageSummary", topLevelMessageSummary(connectionCheck, reviewMemorySmoke));
        payload.put("projectName", project.name());
        payload.put("projectId", project.id());
        payload.put("projectRoot", project.rootPath());
        payload.put("connectionCheck", connectionCheckPayload(connectionCheck));
        payload.put("reactSettingsConnectionCheck", reactConnectionCheckPayload(connectionCheck));
        payload.put("reviewMemorySmoke", reviewMemorySmokePayload(reviewMemorySmoke));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), payload);

        Files.writeString(markdownPath, markdownReport(
                generatedAt,
                project,
                connectionCheck,
                reviewMemorySmoke,
                success,
                topLevelFailureCategory
        ));
        return new AcceptanceReport(jsonPath, markdownPath);
    }

    private Map<String, Object> connectionCheckPayload(ConnectionCheckResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("endpoint", "POST /api/settings/llm/test");
        payload.put("provider", result.provider());
        payload.put("model", result.model());
        payload.put("timeout", result.timeout());
        payload.put("success", result.success());
        payload.put("failureCategory", result.failureCategory());
        payload.put("messageSummary", result.messageSummary());
        payload.put("keyConfigured", result.keyConfigured());
        payload.put("keyStatus", result.keyStatus());
        return payload;
    }

    private Map<String, Object> reactConnectionCheckPayload(ConnectionCheckResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productSurface", "React LLM settings test connection");
        payload.put("sourceEndpoint", "POST /api/settings/llm/test");
        payload.put("contract", "The React settings action displays the same sanitized connection check fields.");
        payload.put("displayedFields", List.of(
                "provider",
                "model",
                "timeout",
                "success",
                "failureCategory",
                "messageSummary",
                "keyStatus",
                "keyConfigured"
        ));
        payload.put("provider", result.provider());
        payload.put("model", result.model());
        payload.put("timeout", result.timeout());
        payload.put("success", result.success());
        payload.put("failureCategory", result.failureCategory());
        payload.put("keyStatus", result.keyStatus());
        return payload;
    }

    private Map<String, Object> reviewMemorySmokePayload(ReviewMemorySmokeResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseName", CASE_NAME);
        payload.put("executed", result.executed());
        payload.put("skipped", result.skipped());
        payload.put("success", result.success());
        payload.put("failureCategory", result.failureCategory());
        payload.put("messageSummary", result.messageSummary());
        payload.put("priorFeedbackMemoryLoaded", result.priorFeedbackMemoryLoaded());
        payload.put("contextCoverage", result.contextCoverage());
        payload.put("contextCoverageSummary", result.contextCoverageSummary());
        payload.put("sourceTypes", result.sourceTypes());
        payload.put("feedbackLoadedSummary", result.feedbackLoadedSummary());
        payload.put("reviewIssuesFilteredSummary", result.reviewIssuesFilteredSummary());
        payload.put("filteredByPriorFeedback", result.filteredByPriorFeedback());
        payload.put("filteredByPriorFeedbackCount", result.filteredByPriorFeedbackCount());
        payload.put("beforeIssueCount", result.beforeIssueCount());
        payload.put("afterIssueCount", result.afterIssueCount());
        payload.put("priorReviewId", result.priorReviewId());
        payload.put("priorIssueId", result.priorIssueId());
        payload.put("laterReviewId", result.laterReviewId());
        payload.put("reviewRunId", result.reviewRunId());
        payload.put("memorySignal", result.memorySignal());
        payload.put("memorySignalType", result.memorySignal().path("signalType").asText(""));
        payload.put("memorySignalStatus", result.memorySignal().path("feedbackStatus").asText(""));
        payload.put("laterOutcomeSummary", result.laterOutcomeSummary());
        return payload;
    }

    private String markdownReport(
            Instant generatedAt,
            Project project,
            ConnectionCheckResult connectionCheck,
            ReviewMemorySmokeResult reviewMemorySmoke,
            boolean success,
            String failureCategory
    ) {
        return """
                # Real LLM Manual Acceptance

                ## Summary

                - Schema: `%s`
                - Version: `%d`
                - Suite: `%s`
                - Mode: `%s`
                - Generated at: `%s`
                - Provider: `%s`
                - Model: `%s`
                - Timeout: `%s`
                - Key status: `%s`
                - Key configured: `%s`
                - Success: `%s`
                - Failure category: `%s`
                - Project: `%s`

                ## Settings Connection Check

                - Endpoint: `POST /api/settings/llm/test`
                - Timeout: `%s`
                - Success: `%s`
                - Failure category: `%s`
                - Message summary: `%s`
                - Key status: `%s`
                - Key configured: `%s`

                ## React Settings Test Connection

                - Product surface: `React LLM settings test connection`
                - Uses endpoint: `POST /api/settings/llm/test`
                - Displayed fields: `provider, model, timeout, success, failureCategory, messageSummary, keyStatus, keyConfigured`
                - Current provider/model/timeout: `%s/%s/%s`

                ## Review Memory Smoke

                - Case: `%s`
                - Executed: `%s`
                - Skipped: `%s`
                - Success: `%s`
                - Failure category: `%s`
                - Message summary: `%s`
                - Prior feedback memory loaded: `%s`
                - Context coverage reviewMemorySignals: `%s`
                - Context source types: `%s`
                - Feedback memory loaded summary: `%s`
                - Context coverage summary: `%s`
                - Issue filtering summary: `%s`
                - Filtered by prior feedback: `%s`
                - Filtered by prior feedback count: `%d`
                - Before issue count: `%d`
                - After issue count: `%d`
                - Prior Review: `%s`
                - Prior Issue: `%s`
                - Later Review: `%s`
                - Review run: `%s`

                ## Harness Note

                This manual acceptance entry is intentionally outside the default Maven test discovery pattern. It reuses the existing LLM settings connection check API and the Review memory real-provider smoke path, and never writes plaintext API keys into the report.
                """.formatted(
                REPORT_SCHEMA,
                REPORT_VERSION,
                SUITE,
                MODE,
                generatedAt,
                inline(connectionCheck.provider()),
                inline(connectionCheck.model()),
                inline(connectionCheck.timeout()),
                inline(connectionCheck.keyStatus()),
                connectionCheck.keyConfigured(),
                success,
                inline(failureCategory),
                inline(project.name()),
                inline(connectionCheck.timeout()),
                connectionCheck.success(),
                inline(connectionCheck.failureCategory()),
                inline(connectionCheck.messageSummary()),
                inline(connectionCheck.keyStatus()),
                connectionCheck.keyConfigured(),
                inline(connectionCheck.provider()),
                inline(connectionCheck.model()),
                inline(connectionCheck.timeout()),
                CASE_NAME,
                reviewMemorySmoke.executed(),
                reviewMemorySmoke.skipped(),
                reviewMemorySmoke.success(),
                inline(reviewMemorySmoke.failureCategory()),
                inline(reviewMemorySmoke.messageSummary()),
                reviewMemorySmoke.priorFeedbackMemoryLoaded(),
                reviewMemorySmoke.contextCoverage().path("reviewMemorySignals").asBoolean(false),
                inline(String.join(", ", reviewMemorySmoke.sourceTypes())),
                inline(reviewMemorySmoke.feedbackLoadedSummary()),
                inline(reviewMemorySmoke.contextCoverageSummary()),
                inline(reviewMemorySmoke.reviewIssuesFilteredSummary()),
                reviewMemorySmoke.filteredByPriorFeedback(),
                reviewMemorySmoke.filteredByPriorFeedbackCount(),
                reviewMemorySmoke.beforeIssueCount(),
                reviewMemorySmoke.afterIssueCount(),
                nullableText(reviewMemorySmoke.priorReviewId()),
                nullableText(reviewMemorySmoke.priorIssueId()),
                nullableText(reviewMemorySmoke.laterReviewId()),
                nullableText(reviewMemorySmoke.reviewRunId())
        );
    }

    private void assertAcceptanceReportContract(
            AcceptanceReport report,
            ConnectionCheckResult connectionCheck,
            ReviewMemorySmokeResult reviewMemorySmoke
    ) throws IOException {
        JsonNode json = objectMapper.readTree(report.jsonPath().toFile());
        assertThat(json.path("schema").asText()).isEqualTo(REPORT_SCHEMA);
        assertThat(json.path("version").asInt()).isEqualTo(REPORT_VERSION);
        assertThat(json.path("suite").asText()).isEqualTo(SUITE);
        assertThat(json.path("mode").asText()).isEqualTo(MODE);
        assertThat(json.path("provider").asText()).isEqualTo(connectionCheck.provider());
        assertThat(json.path("model").asText()).isEqualTo(connectionCheck.model());
        assertThat(json.path("timeout").asText()).isEqualTo(connectionCheck.timeout());
        assertThat(json.path("keyStatus").asText()).isEqualTo(connectionCheck.keyStatus());
        assertThat(json.path("connectionCheck").path("timeout").asText()).isEqualTo(connectionCheck.timeout());
        assertThat(json.path("connectionCheck").path("failureCategory").asText())
                .isEqualTo(connectionCheck.failureCategory());
        assertThat(json.path("connectionCheck").path("messageSummary").asText()).isNotBlank();
        assertThat(json.path("reactSettingsConnectionCheck").path("timeout").asText())
                .isEqualTo(connectionCheck.timeout());
        assertThat(json.path("reactSettingsConnectionCheck").path("displayedFields"))
                .extracting(JsonNode::asText)
                .contains("provider", "model", "timeout", "failureCategory", "messageSummary", "keyStatus", "keyConfigured");
        assertThat(json.path("reviewMemorySmoke").path("failureCategory").asText())
                .isEqualTo(reviewMemorySmoke.failureCategory());
        assertThat(json.path("reviewMemorySmoke").path("contextCoverage").has("reviewMemorySignals")).isTrue();
        assertThat(json.path("reviewMemorySmoke").path("sourceTypes").isArray()).isTrue();

        String markdown = Files.readString(report.markdownPath());
        assertThat(markdown)
                .contains("# Real LLM Manual Acceptance")
                .contains("- Provider: `" + connectionCheck.provider() + "`")
                .contains("- Model: `" + connectionCheck.model() + "`")
                .contains("- Timeout: `" + inline(connectionCheck.timeout()) + "`")
                .contains("- Failure category: `" + json.path("failureCategory").asText() + "`")
                .contains("## Settings Connection Check")
                .contains("## React Settings Test Connection")
                .contains("## Review Memory Smoke");
    }

    private void assertReportDoesNotExposeSecrets(AcceptanceReport report) throws IOException {
        String reportText = Files.readString(report.jsonPath()) + "\n" + Files.readString(report.markdownPath());
        assertSecretAbsent(reportText, llmProperties.gemini().apiKey());
        assertSecretAbsent(reportText, llmProperties.deepseek().apiKey());
    }

    private void assertSecretAbsent(String reportText, String secret) {
        if (secret != null && !secret.isBlank()) {
            assertThat(reportText).doesNotContain(secret);
        }
    }

    private SeededPriorFeedback seedPriorFalsePositiveFeedback(Project project, String feedbackNote) throws Exception {
        AgentRun run = runService.startRun(project.id(), "REAL_LLM_MANUAL_ACCEPTANCE_SEED", "real-llm-manual-acceptance");
        Instant createdAt = Instant.now().minusSeconds(5);
        ReviewRecord review = reviewRecordRepository.save(new ReviewRecord(
                null,
                project.id(),
                run.id(),
                "main",
                "feature/manual-acceptance-prior-feedback",
                "real-llm-manual-acceptance-seed",
                2.4,
                "Seeded prior false-positive feedback for real LLM manual acceptance.",
                null,
                createdAt
        ));
        ReviewIssue issue = reviewIssueRepository.save(new ReviewIssue(
                null,
                review.id(),
                "critical",
                "Possible null dereference",
                "src/main/java/demo/UserService.java",
                12,
                "findById may return null before getName.",
                "Request may fail with NullPointerException.",
                "Use Optional or null check.",
                "high",
                "pending",
                null,
                createdAt,
                createdAt
        ));
        JsonNode updatedIssue = updateIssueStatus(issue.id(), "false_positive", feedbackNote);
        runService.finishRun(run, 0, 0);
        List<ReviewMemorySignal> signals = reviewMemorySignalService.findProjectSignals(project.id(), 8);
        if (signals.isEmpty()) {
            throw new IllegalStateException("Seeded ReviewIssue feedback did not produce a review memory signal.");
        }
        return new SeededPriorFeedback(review.id(), updatedIssue.path("id").asLong(), objectMapper.valueToTree(signals.get(0)));
    }

    private JsonNode createReview(long projectId, String compareBranch, String diffText) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects/{projectId}/reviews", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "manual",
                                  "baseBranch": "main",
                                  "compareBranch": "%s",
                                  "mode": "strict",
                                  "diffText": "%s"
                                }
                                """.formatted(compareBranch, escapeJson(diffText))))
                .andReturn();
        return responseDataOrThrow(result);
    }

    private void createReviewFixture(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.createDirectories(root.resolve(".ai/manual"));
        Files.writeString(root.resolve("pom.xml"), "<project></project>");
        Files.writeString(root.resolve("AGENTS.md"), "Use project AI instructions.");
        Files.writeString(root.resolve(".ai/manual/coding-preferences.md"), "Prefer explicit null handling.");
        Files.writeString(root.resolve("src/main/java/demo/UserService.java"), """
                package demo;

                class UserService {
                    String userName(Long id) {
                        return "";
                    }
                }
                """);
    }

    private JsonNode reviewDetail(long reviewId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/reviews/{reviewId}", reviewId))
                .andReturn();
        return responseDataOrThrow(result);
    }

    private JsonNode reviewEvents(long reviewId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/reviews/{reviewId}/events", reviewId))
                .andReturn();
        return responseDataOrThrow(result);
    }

    private JsonNode updateIssueStatus(long issueId, String status, String note) throws Exception {
        MvcResult result = mockMvc.perform(patch("/api/review-issues/{issueId}", issueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "%s",
                                  "note": "%s"
                                }
                                """.formatted(status, escapeJson(note))))
                .andReturn();
        return responseDataOrThrow(result);
    }

    private JsonNode responseDataOrThrow(MvcResult result) throws IOException {
        int status = result.getResponse().getStatus();
        String responseText = result.getResponse().getContentAsString();
        JsonNode response = responseText == null || responseText.isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(responseText);
        if (status < 200 || status >= 300 || !response.path("success").asBoolean(false)) {
            throw new AcceptanceApiException(
                    status,
                    response.path("errorCode").asText(""),
                    sanitizeMessage(response.path("message").asText(responseText))
            );
        }
        return response.path("data");
    }

    private RunTrace latestReviewRunTrace(Long projectId) {
        List<AgentRun> runs = runService.listRuns(projectId, 1);
        if (runs.isEmpty()) {
            return RunTrace.empty();
        }
        AgentRun run = runs.get(0);
        List<AgentEvent> events = runService.listEvents(run.id());
        String coverageSummary = eventOutput(events, "REVIEW_CONTEXT_COVERAGE_RECORDED");
        return new RunTrace(
                run.id(),
                eventOutput(events, "REVIEW_FEEDBACK_MEMORY_LOADED"),
                coverageSummary,
                eventOutput(events, "REVIEW_ISSUES_FILTERED"),
                sourceTypesFromCoverageSummary(coverageSummary)
        );
    }

    private JsonNode firstSignalOrThrow(JsonNode signals) {
        if (!signals.isArray() || signals.isEmpty()) {
            throw new IllegalStateException("Later Review did not expose a prior feedback review memory signal.");
        }
        return signals.get(0);
    }

    private String eventOutput(JsonNode events, String eventType) {
        for (JsonNode event : events) {
            if (eventType.equals(event.path("eventType").asText())) {
                return event.path("outputSummary").asText("");
            }
        }
        return "";
    }

    private String eventOutput(List<AgentEvent> events, String eventType) {
        for (AgentEvent event : events) {
            if (eventType.equals(event.eventType())) {
                return event.outputSummary() == null ? "" : event.outputSummary();
            }
        }
        return "";
    }

    private FilterCounts parseFilterCounts(String summary, int fallbackAfterIssueCount) {
        if (summary == null) {
            return new FilterCounts(fallbackAfterIssueCount, fallbackAfterIssueCount, false, 0);
        }
        Matcher matcher = FILTER_SUMMARY_PATTERN.matcher(summary);
        if (!matcher.find()) {
            return new FilterCounts(fallbackAfterIssueCount, fallbackAfterIssueCount, false, 0);
        }
        int retained = Integer.parseInt(matcher.group(1));
        int downgraded = Integer.parseInt(matcher.group(2));
        int priorFeedback = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return new FilterCounts(retained + downgraded, fallbackAfterIssueCount, priorFeedback > 0, priorFeedback);
    }

    private JsonNode contextCoverageFromTrace(RunTrace trace) {
        ObjectNode coverage = emptyCoverageNode();
        String summary = trace.contextCoverageSummary();
        if (summary == null || summary.isBlank()) {
            setSourceTypes(coverage, trace.sourceTypes());
            return coverage;
        }
        coverage.put("sourceCount", intFromCoverageSummary(summary, "sourceCount", 0));
        coverage.put("totalTokenEstimate", intFromCoverageSummary(summary, "totalTokenEstimate", 0));
        List<String> parsedSourceTypes = sourceTypesFromCoverageSummary(summary);
        setSourceTypes(coverage, parsedSourceTypes.isEmpty() ? trace.sourceTypes() : parsedSourceTypes);
        coverage.put("reviewRules", booleanFromCoverageSummary(summary, "reviewRules"));
        coverage.put("projectProfile", booleanFromCoverageSummary(summary, "projectProfile"));
        coverage.put("projectGraph", booleanFromCoverageSummary(summary, "projectGraph"));
        coverage.put("reviewMemorySignals", booleanFromCoverageSummary(summary, "reviewMemorySignals"));
        coverage.put("decisionMemory", booleanFromCoverageSummary(summary, "decisionMemory"));
        return coverage;
    }

    private ObjectNode emptyCoverageNode() {
        ObjectNode coverage = objectMapper.createObjectNode();
        coverage.put("sourceCount", 0);
        coverage.put("totalTokenEstimate", 0);
        coverage.set("sourceTypes", objectMapper.createArrayNode());
        coverage.set("sources", objectMapper.createArrayNode());
        coverage.put("reviewRules", false);
        coverage.put("projectProfile", false);
        coverage.put("projectGraph", false);
        coverage.put("reviewMemorySignals", false);
        coverage.put("decisionMemory", false);
        return coverage;
    }

    private void setSourceTypes(ObjectNode coverage, List<String> sourceTypes) {
        ArrayNode values = objectMapper.createArrayNode();
        for (String sourceType : sourceTypes) {
            if (sourceType != null && !sourceType.isBlank()) {
                values.add(sourceType);
            }
        }
        coverage.set("sourceTypes", values);
    }

    private List<String> sourceTypesFromCoverageSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return List.of();
        }
        int start = summary.indexOf("sourceTypes=[");
        if (start < 0) {
            return List.of();
        }
        int valuesStart = start + "sourceTypes=[".length();
        int end = summary.indexOf(']', valuesStart);
        if (end < valuesStart) {
            return List.of();
        }
        String raw = summary.substring(valuesStart, end);
        if (raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String value : raw.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private int intFromCoverageSummary(String summary, String key, int fallback) {
        String value = coverageValue(summary, key);
        if (value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean booleanFromCoverageSummary(String summary, String key) {
        return "true".equalsIgnoreCase(coverageValue(summary, key));
    }

    private String coverageValue(String summary, String key) {
        for (String part : summary.split(";")) {
            String trimmed = part.trim();
            String prefix = key + "=";
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private List<String> jsonTextList(JsonNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            result.add(value.asText());
        }
        return result;
    }

    private boolean realProviderConfigured() {
        return ("gemini".equals(provider()) && hasText(llmProperties.gemini().apiKey()))
                || ("deepseek".equals(provider()) && hasText(llmProperties.deepseek().apiKey()));
    }

    private String classifyFailure(Exception exception) {
        String text = (exception instanceof AcceptanceApiException apiException)
                ? apiException.errorCode() + " " + apiException.getMessage()
                : exception.getMessage();
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, LlmErrorTypes.AUTH_FAILED.toLowerCase(Locale.ROOT),
                "unauthorized", "forbidden", "authentication", "invalid api key", "401", "403")) {
            return LlmErrorTypes.AUTH_FAILED;
        }
        if (containsAny(normalized, LlmErrorTypes.QUOTA_EXCEEDED.toLowerCase(Locale.ROOT),
                "quota", "rate limit", "rate_limit", "429")) {
            return LlmErrorTypes.QUOTA_EXCEEDED;
        }
        if (containsAny(normalized, LlmErrorTypes.TIMEOUT.toLowerCase(Locale.ROOT), "timeout", "timed out")) {
            return LlmErrorTypes.TIMEOUT;
        }
        if (containsAny(normalized, LlmErrorTypes.PROXY_REQUIRED.toLowerCase(Locale.ROOT), "proxy")) {
            return LlmErrorTypes.PROXY_REQUIRED;
        }
        if (containsAny(normalized, LlmErrorTypes.PARSE_FAILED.toLowerCase(Locale.ROOT),
                "parse", "json", "did not include candidates", "did not include choices")) {
            return LlmErrorTypes.PARSE_FAILED;
        }
        if (containsAny(normalized, LlmErrorTypes.PROVIDER_NOT_CONFIGURED.toLowerCase(Locale.ROOT),
                "not configured", "unsupported provider")) {
            return LlmErrorTypes.PROVIDER_NOT_CONFIGURED;
        }
        if (containsAny(normalized, LlmErrorTypes.NETWORK_FAILED.toLowerCase(Locale.ROOT),
                "network", "connect", "connection", "dns", "http", "ssl", "refused", "reset", "unreachable")) {
            return LlmErrorTypes.NETWORK_FAILED;
        }
        return FAILURE_CATEGORY_UNKNOWN;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String topLevelFailureCategory(ConnectionCheckResult connectionCheck, ReviewMemorySmokeResult reviewMemorySmoke) {
        if (!connectionCheck.success()) {
            return connectionCheck.failureCategory();
        }
        if (!reviewMemorySmoke.success() && !reviewMemorySmoke.skipped()) {
            return reviewMemorySmoke.failureCategory();
        }
        if (reviewMemorySmoke.skipped() && !FAILURE_CATEGORY_NONE.equals(reviewMemorySmoke.failureCategory())) {
            return reviewMemorySmoke.failureCategory();
        }
        return FAILURE_CATEGORY_NONE;
    }

    private String acceptanceStatus(
            ConnectionCheckResult connectionCheck,
            ReviewMemorySmokeResult reviewMemorySmoke,
            boolean success
    ) {
        if (success) {
            return "passed";
        }
        if (!connectionCheck.success()) {
            return "failed";
        }
        return reviewMemorySmoke.skipped() ? "skipped" : "failed";
    }

    private String topLevelMessageSummary(ConnectionCheckResult connectionCheck, ReviewMemorySmokeResult reviewMemorySmoke) {
        if (!connectionCheck.success()) {
            return connectionCheck.messageSummary();
        }
        if (!reviewMemorySmoke.success()) {
            return reviewMemorySmoke.messageSummary();
        }
        return "Real LLM manual acceptance completed.";
    }

    private String sanitizeMessage(String message) {
        String safe = llmProperties.maskSecrets(message == null || message.isBlank()
                ? "Real LLM manual acceptance step did not provide a message."
                : message);
        safe = safe.replaceAll("(?i)(authorization\\s*:\\s*bearer\\s+)[^\\s,;]+", "$1[masked]");
        safe = safe.replaceAll("(?i)(api[-_ ]?key\\s*[:=]\\s*)[^\\s,;}\\]]+", "$1[masked]");
        safe = safe.replaceAll("(?i)AIza[0-9A-Za-z_-]{16,}", "[masked]");
        safe = safe.replaceAll("(?i)sk-[0-9A-Za-z_-]{16,}", "[masked]");
        safe = safe.replaceAll("\\s+", " ").trim();
        if (safe.length() > 300) {
            return safe.substring(0, 300);
        }
        return safe;
    }

    private String escapeJson(String value) throws IOException {
        return objectMapper.writeValueAsString(value).replaceFirst("^\"", "").replaceFirst("\"$", "");
    }

    private String provider() {
        return safeText(llmProperties.provider());
    }

    private String model() {
        return safeText(llmProperties.modelName());
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private String inline(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace("`", "'");
    }

    private String nullableText(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record AcceptanceReport(Path jsonPath, Path markdownPath) {
    }

    private record ConnectionCheckResult(
            String provider,
            String model,
            String timeout,
            boolean success,
            String failureCategory,
            String messageSummary,
            boolean keyConfigured,
            String keyStatus
    ) {
    }

    private record SeededPriorFeedback(Long reviewId, Long issueId, JsonNode memorySignal) {
    }

    private record FilterCounts(
            int beforeIssueCount,
            int afterIssueCount,
            boolean filteredByPriorFeedback,
            int filteredByPriorFeedbackCount
    ) {
    }

    private record RunTrace(
            Long runId,
            String feedbackLoadedSummary,
            String contextCoverageSummary,
            String filteredSummary,
            List<String> sourceTypes
    ) {

        static RunTrace empty() {
            return new RunTrace(null, "", "", "", List.of());
        }
    }

    private static class AcceptanceApiException extends RuntimeException {

        private final String errorCode;

        AcceptanceApiException(int statusCode, String errorCode, String message) {
            super("Acceptance API failed with HTTP " + statusCode + ": " + errorCode + " " + message);
            this.errorCode = errorCode;
        }

        String errorCode() {
            return errorCode;
        }
    }

    private class ReviewMemorySmokeResult {

        private boolean executed;
        private boolean skipped;
        private boolean success;
        private String failureCategory = FAILURE_CATEGORY_NONE;
        private String messageSummary = "";
        private Long priorReviewId;
        private Long priorIssueId;
        private Long laterReviewId;
        private Long reviewRunId;
        private JsonNode memorySignal = objectMapper.nullNode();
        private JsonNode contextCoverage = emptyCoverageNode();
        private List<String> sourceTypes = List.of();
        private String feedbackLoadedSummary = "";
        private String contextCoverageSummary = "";
        private String reviewIssuesFilteredSummary = "";
        private boolean priorFeedbackMemoryLoaded;
        private boolean filteredByPriorFeedback;
        private int filteredByPriorFeedbackCount;
        private int beforeIssueCount;
        private int afterIssueCount;
        private JsonNode laterOutcomeSummary = objectMapper.nullNode();

        boolean executed() {
            return executed;
        }

        boolean skipped() {
            return skipped;
        }

        boolean success() {
            return success;
        }

        String failureCategory() {
            return failureCategory;
        }

        String messageSummary() {
            return messageSummary;
        }

        Long priorReviewId() {
            return priorReviewId;
        }

        Long priorIssueId() {
            return priorIssueId;
        }

        Long laterReviewId() {
            return laterReviewId;
        }

        Long reviewRunId() {
            return reviewRunId;
        }

        JsonNode memorySignal() {
            return memorySignal;
        }

        JsonNode contextCoverage() {
            return contextCoverage;
        }

        List<String> sourceTypes() {
            return sourceTypes;
        }

        String feedbackLoadedSummary() {
            return feedbackLoadedSummary;
        }

        String contextCoverageSummary() {
            return contextCoverageSummary;
        }

        String reviewIssuesFilteredSummary() {
            return reviewIssuesFilteredSummary;
        }

        boolean priorFeedbackMemoryLoaded() {
            return priorFeedbackMemoryLoaded;
        }

        boolean filteredByPriorFeedback() {
            return filteredByPriorFeedback;
        }

        int filteredByPriorFeedbackCount() {
            return filteredByPriorFeedbackCount;
        }

        int beforeIssueCount() {
            return beforeIssueCount;
        }

        int afterIssueCount() {
            return afterIssueCount;
        }

        JsonNode laterOutcomeSummary() {
            return laterOutcomeSummary;
        }
    }
}
