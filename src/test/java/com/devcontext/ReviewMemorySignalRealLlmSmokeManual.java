package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        "spring.datasource.url=jdbc:sqlite:target/devcontext-review-memory-real-llm-smoke.sqlite",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
class ReviewMemorySignalRealLlmSmokeManual {

    private static final String REPORT_SCHEMA = "review-memory-signal-smoke";
    private static final int REPORT_VERSION = 1;
    private static final String SUITE = "review-memory-signal-real-llm-smoke";
    private static final String CASE_NAME = "false-positive-suppression-memory";
    private static final String MODE = "strict";
    private static final String FAILURE_CATEGORY_NONE = "none";
    private static final String FAILURE_CATEGORY_UNKNOWN = "unknown";
    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Pattern FILTER_SUMMARY_PATTERN =
            Pattern.compile("(\\d+) issues retained, (\\d+) downgraded(?:, (\\d+) by prior feedback)?");

    static {
        try {
            Path db = Path.of("target/devcontext-review-memory-real-llm-smoke.sqlite");
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
    void manualRealProviderReviewMemorySmokeWritesReport() throws Exception {
        createReviewFixture(projectRoot);
        Project project = projectService.createProject("review-memory-real-llm-single-smoke", projectRoot.toString(), "main");
        if (!realProviderConfigured()) {
            SmokeReport report = writeSkippedReport(
                    project,
                    LlmErrorTypes.PROVIDER_NOT_CONFIGURED,
                    realProviderSkipReason()
            );
            assertSmokeReportDoesNotExposeSecrets(report);
            return;
        }

        String feedbackNote = "Real provider smoke: repository contract already guarantees a non-null user.";
        String diffText = "diff --git a/src/main/java/demo/UserService.java b/src/main/java/demo/UserService.java\n"
                + "+User user = userRepository.findById(id);\n"
                + "+return user.getName();";
        SeededPriorFeedback prior = seedPriorFalsePositiveFeedback(project, feedbackNote);

        try {
            JsonNode secondCreate = createReview(project.id(), "feature/manual-real-llm-later-review", diffText);
            long secondReviewId = secondCreate.path("reviewId").asLong();
            JsonNode secondDetail = reviewDetail(secondReviewId);
            JsonNode secondEvents = reviewEvents(secondReviewId);
            JsonNode signal = secondCreate.path("reviewMemorySignals").get(0);
            String feedbackLoadedSummary = eventOutput(secondEvents.path("events"), "REVIEW_FEEDBACK_MEMORY_LOADED");
            String coverageSummary = eventOutput(secondEvents.path("events"), "REVIEW_CONTEXT_COVERAGE_RECORDED");
            String filteredSummary = eventOutput(secondEvents.path("events"), "REVIEW_ISSUES_FILTERED");
            int afterIssueCount = secondDetail.path("issues").size();
            FilterCounts filterCounts = parseFilterCounts(filteredSummary, afterIssueCount);

            assertThat(secondCreate.path("reviewMemorySignals")).hasSize(1);
            assertThat(signal.path("signalType").asText()).isEqualTo("false_positive_pattern");
            assertThat(signal.path("feedbackStatus").asText()).isEqualTo("false_positive");
            assertThat(signal.path("projectId").asLong()).isEqualTo(project.id());
            assertThat(signal.path("reviewId").asLong()).isEqualTo(prior.reviewId());
            assertThat(signal.path("issueId").asLong()).isEqualTo(prior.issueId());
            assertThat(signal.path("note").asText()).isEqualTo(feedbackNote);
            assertThat(secondCreate.path("contextCoverage").path("reviewMemorySignals").asBoolean()).isTrue();
            assertThat(secondCreate.path("contextCoverage").path("sourceTypes"))
                    .extracting(JsonNode::asText)
                    .contains("REVIEW_FEEDBACK_MEMORY");

            SmokeReport report = writeCompletedReport(
                    project,
                    prior,
                    secondReviewId,
                    secondCreate,
                    secondDetail,
                    signal,
                    feedbackLoadedSummary,
                    coverageSummary,
                    filteredSummary,
                    filterCounts
            );
            assertSmokeReportContract(report, project, prior, secondReviewId);
            assertSmokeReportDoesNotExposeSecrets(report);
        } catch (Exception e) {
            RunTrace trace = latestReviewRunTrace(project.id());
            SmokeReport report = writeFailureReport(
                    project,
                    prior,
                    trace,
                    classifyFailure(e),
                    safeText(e.getMessage())
            );
            assertSmokeReportDoesNotExposeSecrets(report);
            throw e;
        }
    }

    private SeededPriorFeedback seedPriorFalsePositiveFeedback(Project project, String feedbackNote) throws Exception {
        AgentRun run = runService.startRun(project.id(), "REVIEW_MEMORY_REAL_LLM_SMOKE_SEED", "manual-real-llm-smoke-seed");
        Instant createdAt = Instant.now().minusSeconds(5);
        ReviewRecord review = reviewRecordRepository.save(new ReviewRecord(
                null,
                project.id(),
                run.id(),
                "main",
                "feature/manual-prior-false-positive",
                "manual-real-llm-smoke-seed",
                2.4,
                "Seeded prior false-positive feedback for real provider smoke.",
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
        assertThat(signals).hasSize(1);
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

    private JsonNode reviewDetail(long reviewId) throws Exception {
        String response = mockMvc.perform(get("/api/reviews/{reviewId}", reviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode reviewEvents(long reviewId) throws Exception {
        String response = mockMvc.perform(get("/api/reviews/{reviewId}/events", reviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
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
            throw new SmokeApiException(
                    status,
                    response.path("errorCode").asText(""),
                    safeText(response.path("message").asText(responseText))
            );
        }
        return response.path("data");
    }

    private SmokeReport writeSkippedReport(Project project, String failureCategory, String failureMessage) throws IOException {
        SmokeReportData data = new SmokeReportData(project, provider(), model());
        data.skipped = true;
        data.failureCategory = failureCategory;
        data.failureMessage = failureMessage;
        data.smokeOutcome.put("success", false);
        data.smokeOutcome.put("skipped", true);
        data.smokeOutcome.put("failureCategory", failureCategory);
        data.smokeOutcome.put("failureMessage", failureMessage);
        return writeSmokeReport(data);
    }

    private SmokeReport writeFailureReport(
            Project project,
            SeededPriorFeedback prior,
            RunTrace trace,
            String failureCategory,
            String failureMessage
    ) throws IOException {
        SmokeReportData data = new SmokeReportData(project, provider(), model());
        data.failureCategory = failureCategory;
        data.failureMessage = failureMessage;
        data.priorReviewId = prior.reviewId();
        data.priorIssueId = prior.issueId();
        data.memorySignal = prior.memorySignal();
        data.signalCount = 1;
        data.memorySignalType = prior.memorySignal().path("signalType").asText();
        data.memorySignalStatus = prior.memorySignal().path("feedbackStatus").asText();
        data.memorySignalSourceProjectId = prior.memorySignal().path("projectId").asLong();
        data.memorySignalSourceReviewId = prior.memorySignal().path("reviewId").asLong();
        data.memorySignalSourceIssueId = prior.memorySignal().path("issueId").asLong();
        data.runId = trace.runId();
        data.feedbackLoadedSummary = trace.feedbackLoadedSummary();
        data.contextCoverageSummary = trace.contextCoverageSummary();
        data.reviewIssuesFilteredSummary = trace.filteredSummary();
        data.sourceTypes = trace.sourceTypes();
        data.smokeOutcome.put("success", false);
        data.smokeOutcome.put("skipped", false);
        data.smokeOutcome.put("memorySignalLoaded", !trace.feedbackLoadedSummary().isBlank());
        data.smokeOutcome.put("failureCategory", failureCategory);
        data.smokeOutcome.put("failureMessage", failureMessage);
        return writeSmokeReport(data);
    }

    private SmokeReport writeCompletedReport(
            Project project,
            SeededPriorFeedback prior,
            long laterReviewId,
            JsonNode secondCreate,
            JsonNode secondDetail,
            JsonNode signal,
            String feedbackLoadedSummary,
            String coverageSummary,
            String filteredSummary,
            FilterCounts filterCounts
    ) throws IOException {
        SmokeReportData data = new SmokeReportData(project, provider(), model());
        data.priorReviewId = prior.reviewId();
        data.priorIssueId = prior.issueId();
        data.laterReviewId = laterReviewId;
        data.runId = secondCreate.path("runId").asLong();
        data.signalCount = secondCreate.path("reviewMemorySignals").size();
        data.memorySignalType = signal.path("signalType").asText();
        data.memorySignalStatus = signal.path("feedbackStatus").asText();
        data.memorySignalSourceProjectId = signal.path("projectId").asLong();
        data.memorySignalSourceReviewId = signal.path("reviewId").asLong();
        data.memorySignalSourceIssueId = signal.path("issueId").asLong();
        data.contextCoverage = secondCreate.path("contextCoverage");
        data.sourceTypes = jsonTextList(secondCreate.path("contextCoverage").path("sourceTypes"));
        data.memorySignal = signal;
        data.beforeIssueCount = filterCounts.beforeIssueCount();
        data.afterIssueCount = filterCounts.afterIssueCount();
        data.laterOutcomeSummary = secondDetail.path("outcomeSummary");
        data.feedbackLoadedSummary = feedbackLoadedSummary;
        data.contextCoverageSummary = coverageSummary;
        data.reviewIssuesFilteredSummary = filteredSummary;
        data.filteredByPriorFeedback = filterCounts.filteredByPriorFeedback();
        data.filteredByPriorFeedbackCount = filterCounts.filteredByPriorFeedbackCount();
        data.smokeOutcome.put("success", true);
        data.smokeOutcome.put("skipped", false);
        data.smokeOutcome.put("memorySignalLoaded", true);
        data.smokeOutcome.put("suppressionAffectedLaterReview", filterCounts.filteredByPriorFeedback());
        data.smokeOutcome.put("beforeIssueCount", filterCounts.beforeIssueCount());
        data.smokeOutcome.put("afterIssueCount", filterCounts.afterIssueCount());
        data.smokeOutcome.put("filteredByPriorFeedback", filterCounts.filteredByPriorFeedback());
        data.smokeOutcome.put("filteredByPriorFeedbackCount", filterCounts.filteredByPriorFeedbackCount());
        data.smokeOutcome.put("failureCategory", FAILURE_CATEGORY_NONE);
        data.smokeOutcome.put("laterReviewRetainedIssues", filterCounts.afterIssueCount());
        return writeSmokeReport(data);
    }

    private SmokeReport writeSmokeReport(SmokeReportData data) throws IOException {
        Instant generatedAt = Instant.now();
        String runId = "review-memory-real-llm-smoke-" + RUN_ID_FORMAT.format(generatedAt);
        Path reportDir = Path.of("target", "review-memory-signal-real-llm-smoke");
        Files.createDirectories(reportDir);
        Path jsonPath = reportDir.resolve(runId + ".json");
        Path markdownPath = reportDir.resolve(runId + ".md");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", REPORT_SCHEMA);
        payload.put("version", REPORT_VERSION);
        payload.put("runId", runId);
        payload.put("runTimestamp", generatedAt.toString());
        payload.put("generatedAt", generatedAt.toString());
        payload.put("suite", SUITE);
        payload.put("mode", MODE);
        payload.put("provider", data.provider);
        payload.put("model", data.model);
        payload.put("caseName", CASE_NAME);
        payload.put("manualRealProviderSmoke", true);
        payload.put("skipped", data.skipped);
        payload.put("harnessNote", "Manual real-provider Spring smoke. It seeds one prior false_positive ReviewIssue, runs one later Review through the configured real LLM provider, and writes the stable Review memory smoke report contract.");
        payload.put("projectName", data.project.name());
        payload.put("projectId", data.project.id());
        payload.put("projectRoot", data.project.rootPath());
        payload.put("priorReviewId", data.priorReviewId);
        payload.put("priorIssueId", data.priorIssueId);
        payload.put("laterReviewId", data.laterReviewId);
        payload.put("reviewRunId", data.runId);
        payload.put("signalCount", data.signalCount);
        payload.put("memorySignalType", data.memorySignalType);
        payload.put("memorySignalStatus", data.memorySignalStatus);
        payload.put("memorySignalSourceProjectId", data.memorySignalSourceProjectId);
        payload.put("memorySignalSourceReviewId", data.memorySignalSourceReviewId);
        payload.put("memorySignalSourceIssueId", data.memorySignalSourceIssueId);
        payload.put("contextCoverage", data.contextCoverage);
        payload.put("sourceTypes", data.sourceTypes);
        payload.put("memorySignal", data.memorySignal);
        payload.put("beforeIssueCount", data.beforeIssueCount);
        payload.put("afterIssueCount", data.afterIssueCount);
        payload.put("laterIssueCount", data.afterIssueCount);
        payload.put("laterOutcomeSummary", data.laterOutcomeSummary);
        payload.put("feedbackLoadedSummary", data.feedbackLoadedSummary);
        payload.put("contextCoverageSummary", data.contextCoverageSummary);
        payload.put("reviewIssuesFilteredSummary", data.reviewIssuesFilteredSummary);
        payload.put("filteredByPriorFeedback", data.filteredByPriorFeedback);
        payload.put("filteredByPriorFeedbackCount", data.filteredByPriorFeedbackCount);
        payload.put("failureCategory", data.failureCategory);
        payload.put("failureMessage", data.failureMessage);
        payload.put("smokeOutcome", data.smokeOutcome);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), payload);

        Files.writeString(markdownPath, markdownReport(data, generatedAt));
        return new SmokeReport(jsonPath, markdownPath);
    }

    private String markdownReport(SmokeReportData data, Instant generatedAt) {
        return """
                # Review Memory Signal Real LLM Smoke

                ## Contract Summary

                - Schema: `%s`
                - Version: `%d`
                - Suite: `%s`
                - Mode: `%s`
                - Provider: `%s`
                - Model: `%s`
                - Run timestamp: `%s`
                - Project: `%s`
                - Case: `%s`
                - Failure category: `%s`
                - Skipped: `%s`

                ## Outcome

                - Success: `%s`
                - Smoke path: seeded prior `false_positive` feedback memory signal -> later real-provider Review
                - Filtered by prior feedback: `%s`
                - Filtered by prior feedback count: `%d`
                - Before issue count: `%d`
                - After issue count: `%d`
                - Prior Review: `%s`
                - Prior Issue: `%s`
                - Later Review: `%s`
                - Review run: `%s`
                - Review memory signal count: `%d`
                - Later retained issues: `%d`
                - Context coverage reviewMemorySignals: `%s`
                - Context source types: `%s`
                - Failure message: `%s`

                ## Memory Signal

                - Signal type: `%s`
                - Feedback status: `%s`
                - Source project id: `%s`
                - Source review id: `%s`
                - Source issue id: `%s`

                ## Trace Evidence

                - Feedback memory loaded: `%s`
                - Context coverage: `%s`
                - Issue filtering: `%s`

                ## Harness Note

                This manual smoke is intentionally outside the default Maven test discovery pattern. It reuses the existing local LLM configuration and never writes API keys into the report.
                """.formatted(
                REPORT_SCHEMA,
                REPORT_VERSION,
                SUITE,
                MODE,
                inline(data.provider),
                inline(data.model),
                generatedAt,
                inline(data.project.name()),
                CASE_NAME,
                inline(data.failureCategory),
                data.skipped,
                data.smokeOutcome.getOrDefault("success", false),
                data.filteredByPriorFeedback,
                data.filteredByPriorFeedbackCount,
                data.beforeIssueCount,
                data.afterIssueCount,
                nullableText(data.priorReviewId),
                nullableText(data.priorIssueId),
                nullableText(data.laterReviewId),
                nullableText(data.runId),
                data.signalCount,
                data.afterIssueCount,
                data.contextCoverage.path("reviewMemorySignals").asBoolean(false),
                inline(String.join(", ", data.sourceTypes)),
                inline(data.failureMessage),
                inline(data.memorySignalType),
                inline(data.memorySignalStatus),
                nullableText(data.memorySignalSourceProjectId),
                nullableText(data.memorySignalSourceReviewId),
                nullableText(data.memorySignalSourceIssueId),
                inline(data.feedbackLoadedSummary),
                inline(data.contextCoverageSummary),
                inline(data.reviewIssuesFilteredSummary)
        );
    }

    private void assertSmokeReportContract(
            SmokeReport report,
            Project project,
            SeededPriorFeedback prior,
            long laterReviewId
    ) throws IOException {
        JsonNode json = objectMapper.readTree(report.jsonPath().toFile());
        assertThat(json.path("schema").asText()).isEqualTo(REPORT_SCHEMA);
        assertThat(json.path("version").asInt()).isEqualTo(REPORT_VERSION);
        assertThat(json.path("suite").asText()).isEqualTo(SUITE);
        assertThat(json.path("mode").asText()).isEqualTo(MODE);
        assertThat(json.path("provider").asText()).isEqualTo(provider());
        assertThat(json.path("model").asText()).isEqualTo(model());
        assertThat(json.path("runTimestamp").asText()).isNotBlank();
        assertThat(json.path("projectName").asText()).isEqualTo(project.name());
        assertThat(json.path("caseName").asText()).isEqualTo(CASE_NAME);
        assertThat(json.path("memorySignalType").asText()).isEqualTo("false_positive_pattern");
        assertThat(json.path("memorySignalStatus").asText()).isEqualTo("false_positive");
        assertThat(json.path("memorySignalSourceProjectId").asLong()).isEqualTo(project.id());
        assertThat(json.path("memorySignalSourceReviewId").asLong()).isEqualTo(prior.reviewId());
        assertThat(json.path("memorySignalSourceIssueId").asLong()).isEqualTo(prior.issueId());
        assertThat(json.path("contextCoverage").path("reviewMemorySignals").asBoolean()).isTrue();
        assertThat(json.path("sourceTypes")).extracting(JsonNode::asText).contains("REVIEW_FEEDBACK_MEMORY");
        assertThat(json.path("failureCategory").asText()).isEqualTo(FAILURE_CATEGORY_NONE);
        assertThat(json.path("laterReviewId").asLong()).isEqualTo(laterReviewId);

        String markdown = Files.readString(report.markdownPath());
        assertThat(markdown)
                .contains("## Contract Summary")
                .contains("- Schema: `" + REPORT_SCHEMA + "`")
                .contains("- Version: `" + REPORT_VERSION + "`")
                .contains("- Suite: `" + SUITE + "`")
                .contains("- Mode: `" + MODE + "`")
                .contains("- Provider: `" + provider() + "`")
                .contains("- Model: `" + model() + "`")
                .contains("- Case: `" + CASE_NAME + "`")
                .contains("- Failure category: `" + FAILURE_CATEGORY_NONE + "`")
                .contains("- Signal type: `false_positive_pattern`")
                .contains("- Feedback status: `false_positive`")
                .contains("- Source project id: `" + project.id() + "`")
                .contains("- Source review id: `" + prior.reviewId() + "`")
                .contains("- Source issue id: `" + prior.issueId() + "`")
                .contains("REVIEW_FEEDBACK_MEMORY");
    }

    private void assertSmokeReportDoesNotExposeSecrets(SmokeReport report) throws IOException {
        String reportText = Files.readString(report.jsonPath()) + "\n" + Files.readString(report.markdownPath());
        assertSecretAbsent(reportText, llmProperties.gemini().apiKey());
        assertSecretAbsent(reportText, llmProperties.deepseek().apiKey());
    }

    private void assertSecretAbsent(String reportText, String secret) {
        if (secret != null && !secret.isBlank()) {
            assertThat(reportText).doesNotContain(secret);
        }
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

    private String realProviderSkipReason() {
        if ("mock".equals(provider())) {
            return "Skipped because the active LLM provider is mock. Set devcontext.llm.provider to gemini or deepseek with a configured API key to run the manual real-provider smoke.";
        }
        if (!llmProperties.supportedProvider()) {
            return "Skipped because the active LLM provider is not supported by the manual real-provider smoke.";
        }
        return "Skipped because the active real LLM provider does not have a configured API key.";
    }

    private String classifyFailure(Exception exception) {
        String text = (exception instanceof SmokeApiException apiException)
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

    private String provider() {
        return safeText(llmProperties.provider());
    }

    private String model() {
        return safeText(llmProperties.modelName());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        String masked = llmProperties.maskSecrets(value);
        masked = masked.replaceAll("(?i)(api[-_ ]?key|x-goog-api-key|authorization|bearer)(\\s*[:=]?\\s*)\\S+", "$1$2[masked]");
        masked = masked.replaceAll("sk-[A-Za-z0-9_\\-]{8,}", "[masked-key]");
        masked = masked.replaceAll("AIza[A-Za-z0-9_\\-]{10,}", "[masked-key]");
        return masked;
    }

    private String inline(String value) {
        String text = safeText(value).replace('\r', ' ').replace('\n', ' ').replace('`', '\'').trim();
        if (text.length() > 300) {
            return text.substring(0, 300) + "...";
        }
        return text;
    }

    private String nullableText(Long value) {
        return value == null ? "" : value.toString();
    }

    private ObjectNode emptyCoverageNode() {
        ObjectNode coverage = objectMapper.createObjectNode();
        coverage.put("sourceCount", 0);
        coverage.put("totalTokenEstimate", 0);
        ArrayNode sourceTypes = objectMapper.createArrayNode();
        coverage.set("sourceTypes", sourceTypes);
        coverage.set("sources", objectMapper.createArrayNode());
        coverage.put("reviewRules", false);
        coverage.put("projectProfile", false);
        coverage.put("projectGraph", false);
        coverage.put("reviewMemorySignals", false);
        coverage.put("decisionMemory", false);
        return coverage;
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

    private String escapeJson(String value) throws IOException {
        return objectMapper.writeValueAsString(value).replaceFirst("^\"", "").replaceFirst("\"$", "");
    }

    private record SmokeReport(Path jsonPath, Path markdownPath) {
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

    private static class SmokeApiException extends RuntimeException {

        private final int statusCode;
        private final String errorCode;

        SmokeApiException(int statusCode, String errorCode, String message) {
            super("Review API failed with HTTP " + statusCode + ": " + errorCode + " " + message);
            this.statusCode = statusCode;
            this.errorCode = errorCode;
        }

        String errorCode() {
            return errorCode;
        }

        int statusCode() {
            return statusCode;
        }
    }

    private class SmokeReportData {

        private final Project project;
        private final String provider;
        private final String model;
        private boolean skipped;
        private String failureCategory = FAILURE_CATEGORY_NONE;
        private String failureMessage = "";
        private Long priorReviewId;
        private Long priorIssueId;
        private Long laterReviewId;
        private Long runId;
        private int signalCount;
        private String memorySignalType = "";
        private String memorySignalStatus = "";
        private Long memorySignalSourceProjectId;
        private Long memorySignalSourceReviewId;
        private Long memorySignalSourceIssueId;
        private JsonNode contextCoverage = emptyCoverageNode();
        private List<String> sourceTypes = List.of();
        private JsonNode memorySignal = objectMapper.nullNode();
        private int beforeIssueCount;
        private int afterIssueCount;
        private JsonNode laterOutcomeSummary = objectMapper.nullNode();
        private String feedbackLoadedSummary = "";
        private String contextCoverageSummary = "";
        private String reviewIssuesFilteredSummary = "";
        private boolean filteredByPriorFeedback;
        private int filteredByPriorFeedbackCount;
        private final Map<String, Object> smokeOutcome = new LinkedHashMap<>();

        private SmokeReportData(Project project, String provider, String model) {
            this.project = project;
            this.provider = provider;
            this.model = model;
        }

    }
}
