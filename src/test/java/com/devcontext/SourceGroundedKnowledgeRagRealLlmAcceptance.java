package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.application.knowledge.CreateKnowledgeSourceCommand;
import com.devcontext.application.knowledge.KnowledgeIndexApplicationService;
import com.devcontext.application.knowledge.RagAnswerApplicationService;
import com.devcontext.application.knowledge.RagAskCommand;
import com.devcontext.application.llm.LlmErrorTypes;
import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.knowledge.EvidenceEvaluation;
import com.devcontext.domain.knowledge.KnowledgeIndexResult;
import com.devcontext.domain.knowledge.KnowledgeRunDetail;
import com.devcontext.domain.knowledge.KnowledgeSearchResult;
import com.devcontext.domain.knowledge.KnowledgeSource;
import com.devcontext.domain.knowledge.RagAnswerResult;
import com.devcontext.domain.project.Project;
import com.devcontext.domain.run.AgentEvent;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        "devcontext.vector.provider=jdbc"
})
class SourceGroundedKnowledgeRagRealLlmAcceptance {

    private static final String REPORT_SCHEMA = "source-grounded-knowledge-rag-product-sel-real-llm-acceptance";
    private static final int REPORT_VERSION = 4;
    private static final String SUITE = "knowledge-rag-product-source-evidence-loop";
    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Path TEST_DATABASE = Path.of(
            "target",
            "devcontext-source-grounded-rag-real-llm-" + RUN_ID_FORMAT.format(Instant.now()) + ".sqlite"
    );
    private static final Pattern SOURCE_MARKER_PATTERN = Pattern.compile("\\[S(\\d+)]");
    private static final Pattern ANSWER_SOURCE_PATH_PATTERN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_./\\\\-])(?:(?:src|config|frontend|scripts|docs|\\.ai)[/\\\\]"
                    + "[A-Za-z0-9_./\\\\-]+\\.(?:java|sql|xml|yml|yaml|properties|json|md|ts|tsx|js|py|rs|go|cpp|h|kt)"
                    + "|pom\\.xml|build\\.gradle(?:\\.kts)?|docker-compose\\.ya?ml|application\\.ya?ml)"
    );
    private static final List<String> REQUIRED_SCORE_REASONS = List.of(
            "source_evidence_loop",
            "evidence_pack_only",
            "primary_source_only"
    );
    private static final List<String> DATABASE_CHAIN_PATHS = List.of(
            "src/main/resources/db/schema.sql",
            "src/main/java/com/devcontext/adapters/persistence/JdbcAgentEventRepository.java",
            "src/main/java/com/devcontext/domain/run/AgentEvent.java",
            "src/main/java/com/devcontext/adapters/persistence/JdbcSchemaMigrationRunner.java"
    );

    static {
        try {
            Files.deleteIfExists(TEST_DATABASE);
            Files.deleteIfExists(Path.of(TEST_DATABASE + "-shm"));
            Files.deleteIfExists(Path.of(TEST_DATABASE + "-wal"));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + TEST_DATABASE);
    }

    @Autowired
    private ProjectApplicationService projectService;

    @Autowired
    private KnowledgeIndexApplicationService indexService;

    @Autowired
    private RagAnswerApplicationService ragAnswerService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DevContextLlmProperties llmProperties;

    @Test
    void realProviderProductPathSourceEvidenceLoopWritesReport() throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Project project = projectService.createProject(
                "knowledge-rag-product-source-evidence-loop",
                projectRoot.toString(),
                "main"
        );

        if (!realProviderConfigured()) {
            AcceptanceReport report = writeReport(project, null, List.of(), "skipped", providerSkipReason(), null);
            assertJsonReportParseable(report);
            assertReportDoesNotExposeSecrets(report);
            throw new AssertionError(providerSkipReason());
        }

        KnowledgeSource source = indexService.createSource(new CreateKnowledgeSourceCommand(
                "knowledge rag product sel real llm acceptance",
                projectRoot.toString(),
                "project_ai_docs"
        ));
        KnowledgeIndexResult indexResult = indexService.indexSource(source.id());

        List<CaseResult> results = new ArrayList<>();
        for (AcceptanceCase acceptanceCase : acceptanceCases()) {
            results.add(runCase(source.id(), acceptanceCase));
        }

        boolean passed = results.stream().allMatch(CaseResult::passed);
        AcceptanceReport report = writeReport(
                project,
                source,
                results,
                passed ? "passed" : "failed",
                passed ? "All Knowledge RAG product SEL gates passed." : "One or more Knowledge RAG product SEL gates failed.",
                indexResult
        );
        assertJsonReportParseable(report);
        assertReportDoesNotExposeSecrets(report);
        assertThat(results)
                .filteredOn(result -> !result.passed())
                .as("Knowledge RAG product SEL failures are written to " + report.jsonPath())
                .isEmpty();
    }

    private CaseResult runCase(Long sourceId, AcceptanceCase acceptanceCase) {
        try {
            RagAnswerResult answer = ragAnswerService.ask(new RagAskCommand(acceptanceCase.question(), sourceId, 8));
            KnowledgeRunDetail runDetail = ragAnswerService.getRunDetail(answer.runId());
            return evaluate(acceptanceCase, answer, runDetail.events());
        } catch (Exception e) {
            return CaseResult.exception(
                    acceptanceCase,
                    provider(),
                    model(),
                    timeout(),
                    "provider_" + classifyFailure(e),
                    safeText(e.getMessage())
            );
        }
    }

    private CaseResult evaluate(AcceptanceCase acceptanceCase, RagAnswerResult answer, List<AgentEvent> events) {
        EvidenceEvaluation evaluation = answer.evidenceEvaluation();
        List<KnowledgeSearchResult> citations = safeList(answer.citations());
        List<String> eventTypes = events.stream().map(AgentEvent::eventType).toList();
        Map<String, String> eventSummaries = eventSummaries(events);
        List<String> selectedPaths = citationPaths(citations);
        List<String> evidenceGroups = evidenceGroups(citations);
        List<String> scoreReasons = scoreReasons(citations);
        List<String> unsupportedCitationMarkers = unsupportedCitationMarkers(answer.answer(), citations.size());
        List<String> answerSourcePaths = answerSourcePaths(answer.answer());
        List<String> answerNonExistentSourcePaths = nonExistentAnswerSourcePaths(answerSourcePaths);
        List<String> answerUnsupportedSourcePaths = unsupportedAnswerSourcePaths(answerSourcePaths, selectedPaths);
        int generatedManualDocsUsageCount = generatedManualDocsUsageCount(citations, answer.answer());
        int oldRetrievalPrimaryEvidenceCount = oldRetrievalPrimaryEvidenceCount(citations);
        int legacyRetrievalEventCount = legacyRetrievalEventCount(eventTypes);
        boolean sourceEvidenceLoopUsed = eventTypes.contains("KNOWLEDGE_SOURCE_EVIDENCE_LOOP_STARTED");
        boolean sourceEvidenceLoopSupported = eventTypes.contains("KNOWLEDGE_SOURCE_EVIDENCE_LOOP_SUPPORTED");
        boolean fallbackToLegacyRetrieval = fallbackToLegacyRetrieval(eventSummaries);
        boolean evidencePackOnly = eventSummaries.values().stream().anyMatch(value -> value.contains("evidencePackOnly=true"));
        boolean primarySourceOnly = eventSummaries.values().stream().anyMatch(value -> value.contains("primarySourceOnly=true"));
        boolean allCitationsFromSourceEvidenceLoop = citationsFromSourceEvidenceLoop(citations);
        boolean requiredScoreReasonsPresent = scoreReasons.containsAll(REQUIRED_SCORE_REASONS);
        boolean requiredGroupsCovered = containsAllIgnoreCase(evidenceGroups, acceptanceCase.expectedEvidenceGroups());
        boolean requiredPathsCovered = containsAllPaths(selectedPaths, acceptanceCase.requiredSelectedPaths());
        boolean answerMentionsRequiredTerms = containsAllIgnoreCase(List.of(answer.answer()), acceptanceCase.requiredAnswerTerms());
        boolean answerSelfReportsInsufficientEvidence = answerSelfReportsInsufficientEvidence(answer.answer());
        boolean answerHasCitationMarkers = answer.answer() != null && answer.answer().contains("[S");
        boolean answerHasSourcePathCitation = !answerSourcePaths.isEmpty();
        boolean answerSourcePathsExist = answerNonExistentSourcePaths.isEmpty();
        boolean answerSourcePathsFromSelectedEvidence = answerUnsupportedSourcePaths.isEmpty();
        boolean docsGeneratedManualBlocked = generatedManualDocsUsageCount == 0;
        EvidenceChainCheck evidenceChainCheck = acceptanceCase.databaseChainRequired()
                ? databaseChainCheck(citations, answer.answer(), selectedPaths, evidenceGroups)
                : EvidenceChainCheck.notRequired();

        List<String> failures = new ArrayList<>();
        if (!acceptanceCase.expectedIntent().equals(answer.queryPlan().intent())) {
            failures.add("query_plan_failure:intent=" + answer.queryPlan().intent());
        }
        if (!sourceEvidenceLoopUsed) {
            failures.add("source_evidence_loop_trace_failure:missing_started_event");
        }
        if (!sourceEvidenceLoopSupported) {
            failures.add("source_evidence_loop_trace_failure:missing_supported_event");
        }
        if (fallbackToLegacyRetrieval) {
            failures.add("fallback_failure:fallbackToLegacyRetrieval=true");
        }
        if (!evidencePackOnly) {
            failures.add("source_evidence_loop_trace_failure:missing_evidence_pack_only");
        }
        if (!primarySourceOnly) {
            failures.add("source_evidence_loop_trace_failure:missing_primary_source_only");
        }
        if (legacyRetrievalEventCount > 0) {
            failures.add("fallback_failure:legacy_retrieval_events=" + legacyRetrievalEventCount);
        }
        if (!allCitationsFromSourceEvidenceLoop) {
            failures.add("citation_source_failure:non_sel_citation");
        }
        if (!requiredScoreReasonsPresent) {
            failures.add("citation_source_failure:missing_score_reason");
        }
        if (oldRetrievalPrimaryEvidenceCount > 0) {
            failures.add("citation_source_failure:old_retrieval_primary_evidence=" + oldRetrievalPrimaryEvidenceCount);
        }
        if (!docsGeneratedManualBlocked) {
            failures.add("citation_source_failure:docs_generated_manual_usage=" + generatedManualDocsUsageCount);
        }
        if (!requiredGroupsCovered) {
            failures.add("evidence_group_failure:missing=" + missingIgnoreCase(evidenceGroups, acceptanceCase.expectedEvidenceGroups()));
        }
        if (!requiredPathsCovered) {
            failures.add("selected_path_failure:missing=" + missingPaths(selectedPaths, acceptanceCase.requiredSelectedPaths()));
        }
        if (!unsupportedCitationMarkers.isEmpty()) {
            failures.add("citation_failure:unsupported_markers=" + unsupportedCitationMarkers);
        }
        if (!answerHasCitationMarkers) {
            failures.add("citation_failure:missing_answer_marker");
        }
        if (!answerHasSourcePathCitation) {
            failures.add("answer_source_path_failure:missing_source_path");
        }
        if (!answerSourcePathsExist) {
            failures.add("answer_source_path_failure:nonexistent=" + answerNonExistentSourcePaths);
        }
        if (!answerSourcePathsFromSelectedEvidence) {
            failures.add("answer_source_path_failure:unsupported=" + answerUnsupportedSourcePaths);
        }
        if (answerSelfReportsInsufficientEvidence) {
            failures.add("answer_guard_failure:answer_self_reports_insufficient_evidence");
        }
        if (!evaluation.sufficient() || !"supported".equals(evaluation.answerGuardDecision())) {
            failures.add("answer_guard_failure:" + evaluation.answerGuardDecision());
        }
        if (!answerMentionsRequiredTerms) {
            failures.add("llm_answer_failure:missing_required_terms="
                    + missingIgnoreCase(List.of(answer.answer()), acceptanceCase.requiredAnswerTerms()));
        }
        if (!evidenceChainCheck.matched()) {
            failures.add("database_chain_failure:" + evidenceChainCheck.failures());
        }

        return new CaseResult(
                acceptanceCase,
                answer.runId(),
                provider(),
                model(),
                timeout(),
                sourceEvidenceLoopUsed,
                sourceEvidenceLoopSupported,
                evidencePackOnly,
                primarySourceOnly,
                fallbackToLegacyRetrieval,
                allCitationsFromSourceEvidenceLoop,
                requiredScoreReasonsPresent,
                requiredGroupsCovered,
                requiredPathsCovered,
                docsGeneratedManualBlocked,
                answerSelfReportsInsufficientEvidence,
                answerHasSourcePathCitation,
                answerSourcePathsExist,
                answerSourcePathsFromSelectedEvidence,
                evidenceChainCheck,
                evaluation.status(),
                evaluation.answerGuardDecision(),
                answer.queryPlan().intent(),
                unsupportedCitationMarkers.size(),
                generatedManualDocsUsageCount,
                oldRetrievalPrimaryEvidenceCount,
                legacyRetrievalEventCount,
                failures.isEmpty() ? "passed" : "failed",
                failures.isEmpty() ? "none" : failureCategory(failures),
                failures.isEmpty() ? "passed" : String.join("; ", failures),
                selectedPaths,
                evidenceGroups,
                scoreReasons,
                eventTypes,
                eventSummaries,
                unsupportedCitationMarkers,
                answerSourcePaths,
                answerNonExistentSourcePaths,
                answerUnsupportedSourcePaths,
                safeText(answer.answer())
        );
    }

    private EvidenceChainCheck databaseChainCheck(
            List<KnowledgeSearchResult> citations,
            String answer,
            List<String> selectedPaths,
            List<String> evidenceGroups
    ) {
        List<String> failures = new ArrayList<>();
        if (!containsAllPaths(selectedPaths, DATABASE_CHAIN_PATHS)) {
            failures.add("missing_chain_paths=" + missingPaths(selectedPaths, DATABASE_CHAIN_PATHS));
        }
        if (!containsAllIgnoreCase(evidenceGroups, List.of("schema", "repository_sql", "entity_or_model", "migration_runner"))) {
            failures.add("missing_chain_groups="
                    + missingIgnoreCase(evidenceGroups, List.of("schema", "repository_sql", "entity_or_model", "migration_runner")));
        }
        String schemaContent = citationContent(citations, "src/main/resources/db/schema.sql");
        if (!containsIgnoreCase(schemaContent, "agent_event")) {
            failures.add("schema_missing_agent_event");
        }
        String repositoryContent = citationContent(
                citations,
                "src/main/java/com/devcontext/adapters/persistence/JdbcAgentEventRepository.java"
        );
        if (!containsIgnoreCase(repositoryContent, "RowMapper<AgentEvent>")
                || !containsIgnoreCase(repositoryContent, "new AgentEvent(")
                || !containsIgnoreCase(repositoryContent, "agent_event")) {
            failures.add("repository_does_not_construct_agent_event_rows");
        }
        String modelContent = citationContent(citations, "src/main/java/com/devcontext/domain/run/AgentEvent.java");
        if (!containsIgnoreCase(modelContent, "record AgentEvent")) {
            failures.add("model_not_agent_event_record");
        }
        String lowerAnswer = safeText(answer).toLowerCase(Locale.ROOT);
        if (lowerAnswer.contains("codedependency")) {
            failures.add("answer_mentions_codedependency");
        }
        if (selectedPaths.stream().anyMatch(path -> containsIgnoreCase(path, "CodeDependency.java"))) {
            failures.add("selected_codedependency_path");
        }
        if (!containsAllIgnoreCase(
                List.of(answer),
                List.of("agent_event", "JdbcAgentEventRepository", "AgentEvent", "schema")
        )) {
            failures.add("answer_missing_agent_event_chain_terms");
        }
        return new EvidenceChainCheck("agent_event", failures.isEmpty(), List.copyOf(failures));
    }

    private AcceptanceReport writeReport(
            Project project,
            KnowledgeSource source,
            List<CaseResult> results,
            String status,
            String message,
            KnowledgeIndexResult indexResult
    ) throws IOException {
        Instant generatedAt = Instant.now();
        String runId = "source-grounded-knowledge-rag-sel-real-llm-" + RUN_ID_FORMAT.format(generatedAt);
        Path reportDir = Path.of("target", "source-grounded-knowledge-rag-real-llm");
        Files.createDirectories(reportDir);
        Path jsonPath = reportDir.resolve(runId + ".json");
        Path markdownPath = reportDir.resolve(runId + ".md");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", REPORT_SCHEMA);
        payload.put("version", REPORT_VERSION);
        payload.put("suite", SUITE);
        payload.put("runId", runId);
        payload.put("generatedAt", generatedAt.toString());
        payload.put("provider", provider());
        payload.put("model", model());
        payload.put("timeout", timeout());
        payload.put("status", status);
        payload.put("message", message);
        payload.put("caseCount", results.size());
        payload.put("totalCases", results.size());
        payload.put("passedCases", results.stream().filter(CaseResult::passed).count());
        payload.put("failedCases", results.stream().filter(result -> !result.passed()).count());
        payload.put("sourceEvidenceLoopUsedCases", results.stream().filter(CaseResult::sourceEvidenceLoopUsed).count());
        payload.put("fallbackToLegacyRetrievalCases", results.stream().filter(CaseResult::fallbackToLegacyRetrieval).count());
        payload.put("unsupportedCitationCount", results.stream().mapToInt(CaseResult::unsupportedCitationCount).sum());
        payload.put("answerSourcePathMissingCases", results.stream()
                .filter(result -> !result.answerHasSourcePathCitation())
                .count());
        payload.put("nonExistentAnswerSourcePathCount", results.stream()
                .mapToInt(result -> result.answerNonExistentSourcePaths().size())
                .sum());
        payload.put("unsupportedAnswerSourcePathCount", results.stream()
                .mapToInt(result -> result.answerUnsupportedSourcePaths().size())
                .sum());
        payload.put("generatedManualDocsUsageCount", results.stream().mapToInt(CaseResult::generatedManualDocsUsageCount).sum());
        payload.put("oldRetrievalPrimaryEvidenceCount", results.stream().mapToInt(CaseResult::oldRetrievalPrimaryEvidenceCount).sum());
        payload.put("projectId", project.id());
        payload.put("projectName", project.name());
        payload.put("projectRoot", project.rootPath());
        payload.put("sourceId", source == null ? null : source.id());
        payload.put("documentsIndexed", indexResult == null ? 0 : indexResult.documentsIndexed());
        payload.put("chunksIndexed", indexResult == null ? 0 : indexResult.chunksIndexed());
        payload.put("cases", results.stream().map(CaseResult::payload).toList());
        objectMapper.copy()
                .configure(JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature(), true)
                .writerWithDefaultPrettyPrinter()
                .writeValue(jsonPath.toFile(), payload);
        Files.writeString(markdownPath, markdownReport(generatedAt, payload, results));
        return new AcceptanceReport(jsonPath, markdownPath);
    }

    private String markdownReport(Instant generatedAt, Map<String, Object> payload, List<CaseResult> results) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Source-Grounded Knowledge RAG Product SEL Real LLM Acceptance\n\n");
        builder.append("- Schema: `").append(REPORT_SCHEMA).append("`\n");
        builder.append("- Version: `").append(REPORT_VERSION).append("`\n");
        builder.append("- Suite: `").append(SUITE).append("`\n");
        builder.append("- Generated at: `").append(generatedAt).append("`\n");
        builder.append("- Provider/model/timeout: `")
                .append(provider()).append("/")
                .append(model()).append("/")
                .append(timeout()).append("`\n");
        builder.append("- Status: `").append(payload.get("status")).append("`\n");
        builder.append("- Message: `").append(inline(String.valueOf(payload.get("message")))).append("`\n");
        builder.append("- Total/passed/failed: `")
                .append(payload.get("totalCases")).append("/")
                .append(payload.get("passedCases")).append("/")
                .append(payload.get("failedCases")).append("`\n");
        builder.append("- Answer source path missing/non-existent/unsupported: `")
                .append(payload.get("answerSourcePathMissingCases")).append("/")
                .append(payload.get("nonExistentAnswerSourcePathCount")).append("/")
                .append(payload.get("unsupportedAnswerSourcePathCount")).append("`\n");
        builder.append("- Fallback to legacy retrieval cases: `")
                .append(payload.get("fallbackToLegacyRetrievalCases")).append("`\n\n");
        builder.append("| Case | SEL used | fallbackToLegacyRetrieval | Evidence groups | Selected paths | Answer source paths | unsupportedCitationCount | generated/manual/docs usage | failureCategory |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (CaseResult result : results) {
            builder.append("| ")
                    .append(inline(result.acceptanceCase().id()))
                    .append(" | ")
                    .append(result.sourceEvidenceLoopUsed())
                    .append(" | ")
                    .append(result.fallbackToLegacyRetrieval())
                    .append(" | ")
                    .append(inline(String.join(", ", result.evidenceGroups())))
                    .append(" | ")
                    .append(inline(String.join("<br>", result.selectedPaths())))
                    .append(" | ")
                    .append(inline(String.join("<br>", result.answerSourcePaths())))
                    .append(" | ")
                    .append(result.unsupportedCitationCount())
                    .append(" | ")
                    .append(result.generatedManualDocsUsageCount())
                    .append(" | ")
                    .append(inline(result.failureCategory()))
                    .append(" |\n");
        }
        builder.append("\nThis manual acceptance calls `RagAnswerApplicationService.ask` and validates the Knowledge RAG product path uses Source Evidence Loop EvidencePack citations only.\n");
        return builder.toString();
    }

    private List<AcceptanceCase> acceptanceCases() {
        return List.of(
                new AcceptanceCase(
                        "devcontext-context-cn",
                        "上下文生成具体怎么做？核心源码在哪里？",
                        "implementation_detail",
                        List.of("controller_entrypoint", "application_service", "key_model_result", "test_or_contract"),
                        List.of(
                                "src/main/java/com/devcontext/adapters/web/ProjectContextController.java",
                                "src/main/java/com/devcontext/application/context/ProjectContextAssetApplicationService.java",
                                "src/main/java/com/devcontext/domain/context/ContextGenerationResult.java",
                                "src/test/java/com/devcontext/Mvp1ProjectContextAssetsTests.java"
                        ),
                        List.of("ProjectContextController", "ProjectContextAssetApplicationService", "ContextGenerationResult"),
                        false
                ),
                new AcceptanceCase(
                        "devcontext-sql-cn",
                        "SQL 怎么写的？",
                        "database_detail",
                        List.of("schema", "repository_sql", "entity_or_model", "migration_runner"),
                        DATABASE_CHAIN_PATHS,
                        List.of("agent_event", "JdbcAgentEventRepository", "AgentEvent", "schema"),
                        true
                ),
                new AcceptanceCase(
                        "devcontext-evidence-coverage-api-cn",
                        "evidence coverage API 怎么实现？入口、服务、返回模型和测试在哪里？",
                        "implementation_detail",
                        List.of("controller_entrypoint", "application_service", "key_model_result", "test_or_contract"),
                        List.of(
                                "src/main/java/com/devcontext/adapters/web/ProjectContextController.java",
                                "src/main/java/com/devcontext/application/evidence/ProjectEvidenceCatalogApplicationService.java",
                                "src/main/java/com/devcontext/domain/evidence/ProjectEvidenceCoverageSummary.java",
                                "src/test/java/com/devcontext/SourceGroundedKnowledgeRagTests.java"
                        ),
                        List.of("ProjectContextController", "ProjectEvidenceCatalogApplicationService", "ProjectEvidenceCoverageSummary"),
                        false
                ),
                new AcceptanceCase(
                        "devcontext-queryplan-required-evidence-cn",
                        "QueryPlan 怎么设置 required evidence？",
                        "implementation_detail",
                        List.of(),
                        List.of(
                                "src/main/java/com/devcontext/application/knowledge/KnowledgeQueryPlanner.java",
                                "src/main/java/com/devcontext/domain/knowledge/KnowledgeQueryPlan.java",
                                "src/test/java/com/devcontext/KnowledgeQueryPlanContractTests.java"
                        ),
                        List.of("KnowledgeQueryPlanner", "KnowledgeQueryPlan", "required evidence"),
                        false
                ),
                new AcceptanceCase(
                        "devcontext-llm-settings-cn",
                        "LLM settings 怎么切 provider？provider、model、key、timeout 和测试入口在哪里？",
                        "configuration_detail",
                        List.of("properties", "settings_controller", "local_config_store", "provider_client", "test_endpoint"),
                        List.of(
                                "src/main/java/com/devcontext/config/DevContextLlmProperties.java",
                                "src/main/java/com/devcontext/adapters/web/LlmSettingsController.java",
                                "src/main/java/com/devcontext/application/llm/LocalLlmSettingsStore.java",
                                "src/main/java/com/devcontext/adapters/llm/DeepSeekLlmClient.java",
                                "src/test/java/com/devcontext/LlmSettingsControllerTests.java"
                        ),
                        List.of("DevContextLlmProperties", "LlmSettingsController", "LocalLlmSettingsStore", "provider", "timeout"),
                        false
                ),
                new AcceptanceCase(
                        "devcontext-review-feedback-cn",
                        "false positive 反馈怎么影响后续 code review？",
                        "review_context_detail",
                        List.of(
                                "review_application_service",
                                "feedback_memory_signal",
                                "postprocessor_filtering",
                                "review_issue_model",
                                "test_or_smoke"
                        ),
                        List.of(
                                "src/main/java/com/devcontext/application/review/ReviewApplicationService.java",
                                "src/main/java/com/devcontext/application/review/ReviewMemorySignalService.java",
                                "src/main/java/com/devcontext/application/review/ReviewReportPostProcessor.java",
                                "src/main/java/com/devcontext/domain/review/ReviewMemorySignalType.java",
                                "src/test/java/com/devcontext/Mvp2AiCodeReviewTests.java"
                        ),
                        List.of("false positive", "ReviewMemorySignalService", "ReviewReportPostProcessor", "feedback"),
                        false
                ),
                new AcceptanceCase(
                        "devcontext-source-evidence-loop-cn",
                        "SourceEvidenceLoopProbe 和 SourceEvidenceLoopKnowledgeAdapter 是怎么选择 evidence pack 的？核心源码和 contract test 在哪里？",
                        "implementation_detail",
                        List.of(),
                        List.of(
                                "src/main/java/com/devcontext/application/evidence/SourceEvidenceLoopProbe.java",
                                "src/main/java/com/devcontext/application/knowledge/SourceEvidenceLoopKnowledgeAdapter.java",
                                "src/test/java/com/devcontext/SourceEvidenceLoopProbeTests.java"
                        ),
                        List.of("SourceEvidenceLoopProbe", "SourceEvidenceLoopKnowledgeAdapter", "evidence pack"),
                        false
                ),
                new AcceptanceCase(
                        "devcontext-answer-guard-cn",
                        "Knowledge RAG 的 evidence-grounded answer guard 是怎么判断 supported/partial/insufficient 的？",
                        "implementation_detail",
                        List.of(),
                        List.of(
                                "src/main/java/com/devcontext/application/knowledge/KnowledgeEvidenceEvaluationService.java",
                                "src/main/java/com/devcontext/application/knowledge/RagAnswerApplicationService.java",
                                "src/test/java/com/devcontext/RagEvidenceEvaluationTests.java"
                        ),
                        List.of("KnowledgeEvidenceEvaluationService", "RagAnswerApplicationService", "supported", "partial", "insufficient"),
                        false
                ),
                new AcceptanceCase(
                        "devcontext-controlled-deep-scan-cn",
                        "ControlledDeepScanService 和 SandboxedReadOnlyContextProvider 是怎么补查候选源码的？核心源码和测试在哪里？",
                        "implementation_detail",
                        List.of(),
                        List.of(
                                "src/main/java/com/devcontext/application/knowledge/ControlledDeepScanService.java",
                                "src/main/java/com/devcontext/application/context/SandboxedReadOnlyContextProvider.java",
                                "src/test/java/com/devcontext/ControlledDeepScanTests.java"
                        ),
                        List.of("ControlledDeepScanService", "SandboxedReadOnlyContextProvider", "candidate"),
                        false
                ),
                new AcceptanceCase(
                        "devcontext-knowledge-index-coverage-cn",
                        "knowledge source index 和 evidence coverage report 是在哪里生成的？",
                        "implementation_detail",
                        List.of(),
                        List.of(
                                "src/main/java/com/devcontext/application/knowledge/KnowledgeIndexApplicationService.java",
                                "src/main/java/com/devcontext/application/knowledge/KnowledgeCoverageService.java",
                                "src/main/java/com/devcontext/domain/knowledge/EvidenceCoverageReport.java"
                        ),
                        List.of("KnowledgeIndexApplicationService", "KnowledgeCoverageService", "EvidenceCoverageReport"),
                        false
                )
        );
    }

    private List<String> citationPaths(List<KnowledgeSearchResult> citations) {
        return citations.stream()
                .map(KnowledgeSearchResult::filePath)
                .filter(this::hasText)
                .distinct()
                .toList();
    }

    private List<String> evidenceGroups(List<KnowledgeSearchResult> citations) {
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        for (KnowledgeSearchResult citation : citations) {
            for (String reason : safeList(citation.scoreReasons())) {
                if (reason != null && reason.startsWith("evidence_group:")) {
                    groups.add(reason.substring("evidence_group:".length()));
                }
            }
        }
        return List.copyOf(groups);
    }

    private List<String> scoreReasons(List<KnowledgeSearchResult> citations) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        for (KnowledgeSearchResult citation : citations) {
            reasons.addAll(safeList(citation.scoreReasons()));
        }
        return List.copyOf(reasons);
    }

    private boolean citationsFromSourceEvidenceLoop(List<KnowledgeSearchResult> citations) {
        if (citations.isEmpty()) {
            return false;
        }
        for (KnowledgeSearchResult citation : citations) {
            List<String> reasons = safeList(citation.scoreReasons());
            if (!reasons.containsAll(REQUIRED_SCORE_REASONS)) {
                return false;
            }
            if (!safeText(citation.headingPath()).startsWith("source-evidence-loop/")) {
                return false;
            }
            if (citation.chunkId() != null || citation.documentId() != null) {
                return false;
            }
        }
        return true;
    }

    private int generatedManualDocsUsageCount(List<KnowledgeSearchResult> citations, String answer) {
        int count = 0;
        for (KnowledgeSearchResult citation : citations) {
            if (legacySourcePath(citation.filePath())) {
                count++;
            }
        }
        String lowerAnswer = safeText(answer).toLowerCase(Locale.ROOT);
        for (String marker : List.of(".ai/generated", ".ai/manual", "readme", "docs/", "vector chunk", "legacy retrieval")) {
            if (lowerAnswer.contains(marker)) {
                count++;
            }
        }
        return count;
    }

    private int oldRetrievalPrimaryEvidenceCount(List<KnowledgeSearchResult> citations) {
        int count = 0;
        for (KnowledgeSearchResult citation : citations) {
            List<String> reasons = safeList(citation.scoreReasons());
            if (citation.chunkId() != null
                    || citation.documentId() != null
                    || reasons.stream().anyMatch(reason -> containsAny(
                    normalize(reason),
                    "source_repo_map",
                    "controlled_deep_scan",
                    "retrieval",
                    "vector",
                    "keyword"
            ))) {
                count++;
            }
        }
        return count;
    }

    private int legacyRetrievalEventCount(List<String> eventTypes) {
        int count = 0;
        for (String eventType : eventTypes) {
            if (List.of(
                    "KNOWLEDGE_SOURCE_REPO_MAP_STARTED",
                    "KNOWLEDGE_SOURCE_REPO_MAP_FINISHED",
                    "KNOWLEDGE_DEEP_SCAN_STARTED",
                    "KNOWLEDGE_DEEP_SCAN_FINISHED"
            ).contains(eventType)) {
                count++;
            }
        }
        return count;
    }

    private boolean fallbackToLegacyRetrieval(Map<String, String> eventSummaries) {
        boolean sawExplicitFalse = false;
        for (String value : eventSummaries.values()) {
            if (value.contains("fallbackToLegacyRetrieval=true")) {
                return true;
            }
            if (value.contains("fallbackToLegacyRetrieval=false")) {
                sawExplicitFalse = true;
            }
        }
        return !sawExplicitFalse;
    }

    private List<String> unsupportedCitationMarkers(String answer, int citationCount) {
        LinkedHashSet<String> unsupported = new LinkedHashSet<>();
        Matcher matcher = SOURCE_MARKER_PATTERN.matcher(safeText(answer));
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index < 1 || index > citationCount) {
                unsupported.add("[S" + index + "]");
            }
        }
        return List.copyOf(unsupported);
    }

    private List<String> answerSourcePaths(String answer) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        Matcher matcher = ANSWER_SOURCE_PATH_PATTERN.matcher(safeText(answer));
        while (matcher.find()) {
            paths.add(normalizePath(matcher.group()));
        }
        return List.copyOf(paths);
    }

    private List<String> nonExistentAnswerSourcePaths(List<String> answerSourcePaths) {
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        List<String> missing = new ArrayList<>();
        for (String path : answerSourcePaths) {
            if (!Files.exists(projectRoot.resolve(path).normalize())) {
                missing.add(path);
            }
        }
        return missing;
    }

    private List<String> unsupportedAnswerSourcePaths(List<String> answerSourcePaths, List<String> selectedPaths) {
        List<String> unsupported = new ArrayList<>();
        for (String answerPath : answerSourcePaths) {
            boolean selected = selectedPaths.stream().anyMatch(selectedPath -> samePath(selectedPath, answerPath));
            if (!selected) {
                unsupported.add(answerPath);
            }
        }
        return unsupported;
    }

    private String citationContent(List<KnowledgeSearchResult> citations, String expectedPath) {
        for (KnowledgeSearchResult citation : citations) {
            if (samePath(citation.filePath(), expectedPath)) {
                return safeText(citation.content());
            }
        }
        return "";
    }

    private boolean containsAllIgnoreCase(List<String> haystacks, List<String> needles) {
        for (String needle : needles) {
            boolean found = false;
            for (String haystack : haystacks) {
                if (containsIgnoreCase(haystack, needle)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private List<String> missingIgnoreCase(List<String> haystacks, List<String> needles) {
        List<String> missing = new ArrayList<>();
        for (String needle : needles) {
            if (!containsAllIgnoreCase(haystacks, List.of(needle))) {
                missing.add(needle);
            }
        }
        return List.copyOf(missing);
    }

    private boolean containsAllPaths(List<String> selectedPaths, List<String> requiredPaths) {
        return missingPaths(selectedPaths, requiredPaths).isEmpty();
    }

    private List<String> missingPaths(List<String> selectedPaths, List<String> requiredPaths) {
        List<String> missing = new ArrayList<>();
        for (String requiredPath : requiredPaths) {
            boolean found = selectedPaths.stream().anyMatch(path -> samePath(path, requiredPath));
            if (!found) {
                missing.add(requiredPath);
            }
        }
        return List.copyOf(missing);
    }

    private boolean samePath(String left, String right) {
        return normalizePath(left).equals(normalizePath(right));
    }

    private boolean legacySourcePath(String path) {
        String normalized = normalizePath(path);
        return normalized.startsWith("docs/")
                || normalized.contains("/docs/")
                || normalized.startsWith(".ai/generated/")
                || normalized.startsWith(".ai/manual/")
                || normalized.endsWith("/readme.md")
                || normalized.equals("readme.md")
                || normalized.endsWith(".md");
    }

    private boolean answerSelfReportsInsufficientEvidence(String answer) {
        String lower = safeText(answer).toLowerCase(Locale.ROOT);
        if (containsAny(
                lower,
                "i can only partially answer",
                "i can provide a partial answer",
                "what is missing:",
                "missing evidence required",
                "missing evidence needed",
                "key implementation details are missing",
                "lacks the core implementation logic",
                "insufficient evidence",
                "incomplete evidence",
                "limited evidence",
                "not enough evidence",
                "cannot answer",
                "当前证据不足",
                "当前证据不完整",
                "证据不足",
                "证据不完整",
                "缺少证据",
                "无法回答"
        )) {
            return true;
        }
        for (String line : lower.split("\\R")) {
            boolean currentEvidenceLine = containsAny(
                    line,
                    "retrieved context",
                    "retrieved evidence",
                    "current context",
                    "current evidence",
                    "provided context",
                    "provided evidence",
                    "context does not",
                    "evidence does not",
                    "当前上下文",
                    "当前证据"
            );
            boolean insufficientLine = containsAny(
                    line,
                    "missing",
                    "limited",
                    "not shown",
                    "not present",
                    "does not contain",
                    "does not show",
                    "不足",
                    "不完整",
                    "缺少",
                    "无法"
            );
            if (currentEvidenceLine && insufficientLine) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> eventSummaries(List<AgentEvent> events) {
        Map<String, String> summaries = new LinkedHashMap<>();
        for (AgentEvent event : events) {
            summaries.put(event.eventType(), safeText(event.outputSummary()));
        }
        return summaries;
    }

    private String failureCategory(List<String> failures) {
        for (String failure : failures) {
            if (failure.startsWith("source_evidence_loop_trace_failure")) {
                return "source_evidence_loop_trace_failure";
            }
            if (failure.startsWith("fallback_failure")) {
                return "fallback_failure";
            }
            if (failure.startsWith("citation_source_failure")) {
                return "citation_source_failure";
            }
            if (failure.startsWith("evidence_group_failure")) {
                return "evidence_group_failure";
            }
            if (failure.startsWith("selected_path_failure")) {
                return "selected_path_failure";
            }
            if (failure.startsWith("database_chain_failure")) {
                return "database_chain_failure";
            }
            if (failure.startsWith("citation_failure")) {
                return "citation_failure";
            }
            if (failure.startsWith("answer_source_path_failure")) {
                return "answer_source_path_failure";
            }
            if (failure.startsWith("answer_guard_failure")) {
                return "answer_guard_failure";
            }
            if (failure.startsWith("llm_answer_failure")) {
                return "llm_answer_failure";
            }
            if (failure.startsWith("query_plan_failure")) {
                return "query_plan_failure";
            }
        }
        return "source_evidence_loop_product_path_failure";
    }

    private String classifyFailure(Exception exception) {
        String normalized = safeText(exception.getMessage()).toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "401", "403", "unauthorized", "forbidden", "invalid api key", "authentication")) {
            return LlmErrorTypes.AUTH_FAILED;
        }
        if (containsAny(normalized, "quota", "rate limit", "429")) {
            return LlmErrorTypes.QUOTA_EXCEEDED;
        }
        if (containsAny(normalized, "timeout", "timed out", "504")) {
            return LlmErrorTypes.TIMEOUT;
        }
        if (containsAny(normalized, "network", "connect", "connection", "dns", "ssl", "refused", "reset", "unreachable")) {
            return LlmErrorTypes.NETWORK_FAILED;
        }
        return "unknown";
    }

    private boolean realProviderConfigured() {
        return ("gemini".equals(provider()) && hasText(llmProperties.gemini().apiKey()))
                || ("deepseek".equals(provider()) && hasText(llmProperties.deepseek().apiKey()));
    }

    private String providerSkipReason() {
        if ("mock".equals(provider())) {
            return "LLM_PROVIDER_NOT_CONFIGURED: active provider is mock; configure gemini or deepseek for real LLM acceptance.";
        }
        return "LLM_PROVIDER_NOT_CONFIGURED: active real provider has no configured API key.";
    }

    private void assertJsonReportParseable(AcceptanceReport report) throws IOException {
        assertThat(objectMapper.readTree(report.jsonPath().toFile()).path("schema").asText())
                .isEqualTo(REPORT_SCHEMA);
    }

    private void assertReportDoesNotExposeSecrets(AcceptanceReport report) throws IOException {
        String text = Files.readString(report.jsonPath()) + "\n" + Files.readString(report.markdownPath());
        assertSecretAbsent(text, llmProperties.gemini().apiKey());
        assertSecretAbsent(text, llmProperties.deepseek().apiKey());
    }

    private void assertSecretAbsent(String text, String secret) {
        if (hasText(secret)) {
            assertThat(text).doesNotContain(secret);
        }
    }

    private boolean containsAny(String value, String... needles) {
        String safeValue = value == null ? "" : value;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && safeValue.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null
                && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private String provider() {
        return safeText(llmProperties.provider());
    }

    private String model() {
        return safeText(llmProperties.modelName());
    }

    private String timeout() {
        Duration timeout = switch (provider()) {
            case "gemini" -> llmProperties.gemini().timeout();
            case "deepseek" -> llmProperties.deepseek().timeout();
            default -> Duration.ZERO;
        };
        return timeout.isZero() ? "" : timeout.toString();
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
        String text = safeText(value).replace('\r', ' ').replace('\n', ' ').replace('|', '/').replace('`', '\'').trim();
        return text.length() > 260 ? text.substring(0, 260) + "..." : text;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record AcceptanceReport(Path jsonPath, Path markdownPath) {
    }

    private record AcceptanceCase(
            String id,
            String question,
            String expectedIntent,
            List<String> expectedEvidenceGroups,
            List<String> requiredSelectedPaths,
            List<String> requiredAnswerTerms,
            boolean databaseChainRequired
    ) {
    }

    private record EvidenceChainCheck(String evidenceChainName, boolean matched, List<String> failures) {

        private static EvidenceChainCheck notRequired() {
            return new EvidenceChainCheck("", true, List.of());
        }
    }

    private record CaseResult(
            AcceptanceCase acceptanceCase,
            Long runId,
            String provider,
            String model,
            String timeout,
            boolean sourceEvidenceLoopUsed,
            boolean sourceEvidenceLoopSupported,
            boolean evidencePackOnly,
            boolean primarySourceOnly,
            boolean fallbackToLegacyRetrieval,
            boolean citationsFromSourceEvidenceLoop,
            boolean requiredScoreReasonsPresent,
            boolean requiredGroupsCovered,
            boolean requiredPathsCovered,
            boolean docsGeneratedManualBlocked,
            boolean answerSelfReportsInsufficientEvidence,
            boolean answerHasSourcePathCitation,
            boolean answerSourcePathsExist,
            boolean answerSourcePathsFromSelectedEvidence,
            EvidenceChainCheck evidenceChainCheck,
            String evidenceEvaluationStatus,
            String answerGuardDecision,
            String queryPlanIntent,
            int unsupportedCitationCount,
            int generatedManualDocsUsageCount,
            int oldRetrievalPrimaryEvidenceCount,
            int legacyRetrievalEventCount,
            String status,
            String failureCategory,
            String message,
            List<String> selectedPaths,
            List<String> evidenceGroups,
            List<String> scoreReasons,
            List<String> eventTypes,
            Map<String, String> trace,
            List<String> unsupportedCitationMarkers,
            List<String> answerSourcePaths,
            List<String> answerNonExistentSourcePaths,
            List<String> answerUnsupportedSourcePaths,
            String answer
    ) {
        static CaseResult exception(
                AcceptanceCase acceptanceCase,
                String provider,
                String model,
                String timeout,
                String failureCategory,
                String message
        ) {
            return new CaseResult(
                    acceptanceCase,
                    null,
                    provider,
                    model,
                    timeout,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    true,
                    true,
                    EvidenceChainCheck.notRequired(),
                    "",
                    "",
                    "",
                    0,
                    0,
                    0,
                    0,
                    "failed",
                    failureCategory,
                    message,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    ""
            );
        }

        boolean passed() {
            return "passed".equals(status);
        }

        Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", acceptanceCase.id());
            payload.put("question", acceptanceCase.question());
            payload.put("expectedIntent", acceptanceCase.expectedIntent());
            payload.put("expectedEvidenceGroups", acceptanceCase.expectedEvidenceGroups());
            payload.put("requiredSelectedPaths", acceptanceCase.requiredSelectedPaths());
            payload.put("requiredAnswerTerms", acceptanceCase.requiredAnswerTerms());
            payload.put("runId", runId);
            payload.put("provider", provider);
            payload.put("model", model);
            payload.put("timeout", timeout);
            payload.put("status", status);
            payload.put("failureCategory", failureCategory);
            payload.put("message", message);
            payload.put("queryPlanIntent", queryPlanIntent);
            payload.put("evidenceEvaluationStatus", evidenceEvaluationStatus);
            payload.put("answerGuardDecision", answerGuardDecision);
            payload.put("sourceEvidenceLoopUsed", sourceEvidenceLoopUsed);
            payload.put("sourceEvidenceLoopSupported", sourceEvidenceLoopSupported);
            payload.put("evidencePackOnly", evidencePackOnly);
            payload.put("primarySourceOnly", primarySourceOnly);
            payload.put("fallbackToLegacyRetrieval", fallbackToLegacyRetrieval);
            payload.put("citationsFromSourceEvidenceLoop", citationsFromSourceEvidenceLoop);
            payload.put("requiredScoreReasonsPresent", requiredScoreReasonsPresent);
            payload.put("requiredGroupsCovered", requiredGroupsCovered);
            payload.put("requiredPathsCovered", requiredPathsCovered);
            payload.put("docsGeneratedManualBlocked", docsGeneratedManualBlocked);
            payload.put("answerSelfReportsInsufficientEvidence", answerSelfReportsInsufficientEvidence);
            payload.put("answerHasSourcePathCitation", answerHasSourcePathCitation);
            payload.put("answerSourcePathsExist", answerSourcePathsExist);
            payload.put("answerSourcePathsFromSelectedEvidence", answerSourcePathsFromSelectedEvidence);
            payload.put("unsupportedCitationCount", unsupportedCitationCount);
            payload.put("generatedManualDocsUsageCount", generatedManualDocsUsageCount);
            payload.put("oldRetrievalPrimaryEvidenceCount", oldRetrievalPrimaryEvidenceCount);
            payload.put("legacyRetrievalEventCount", legacyRetrievalEventCount);
            payload.put("selectedPaths", selectedPaths);
            payload.put("evidenceGroups", evidenceGroups);
            payload.put("scoreReasons", scoreReasons);
            payload.put("eventTypes", eventTypes);
            payload.put("trace", trace);
            payload.put("unsupportedCitationMarkers", unsupportedCitationMarkers);
            payload.put("answerSourcePaths", answerSourcePaths);
            payload.put("answerNonExistentSourcePaths", answerNonExistentSourcePaths);
            payload.put("answerUnsupportedSourcePaths", answerUnsupportedSourcePaths);
            payload.put("evidenceChainName", evidenceChainCheck.evidenceChainName());
            payload.put("evidenceChainMatched", evidenceChainCheck.matched());
            payload.put("evidenceChainFailures", evidenceChainCheck.failures());
            payload.put("answer", answer);
            return payload;
        }
    }
}
