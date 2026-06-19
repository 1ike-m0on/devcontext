package com.devcontext.application.knowledge;

import com.devcontext.application.evidence.SourceEvidenceLoopProbe.EvidenceFragment;
import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.knowledge.EvidenceEvaluation;
import com.devcontext.domain.knowledge.KnowledgeEvidenceType;
import com.devcontext.domain.knowledge.KnowledgeRunDetail;
import com.devcontext.domain.knowledge.KnowledgeSearchResponse;
import com.devcontext.domain.knowledge.KnowledgeSearchResult;
import com.devcontext.domain.knowledge.KnowledgeSource;
import com.devcontext.domain.knowledge.RagAnswerResult;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.domain.run.AgentRun;
import com.devcontext.ports.knowledge.KnowledgeSourceRepository;
import com.devcontext.ports.knowledge.RetrievalRecordRepository;
import com.devcontext.ports.llm.LlmClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class RagAnswerApplicationService {

    private static final Pattern ANSWER_SOURCE_PATH_PATTERN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_./\\\\-])(?:(?:src|config|frontend|scripts|docs|\\.ai)[/\\\\]"
                    + "[A-Za-z0-9_./\\\\-]+\\.(?:java|sql|xml|yml|yaml|properties|json|md|ts|tsx|js|py|rs|go|cpp|h|kt)"
                    + "|pom\\.xml|build\\.gradle(?:\\.kts)?|docker-compose\\.ya?ml|application\\.ya?ml)"
    );

    private final KnowledgeSearchApplicationService searchService;
    private final RagPromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final DevContextLlmProperties llmProperties;
    private final AgentRunApplicationService runService;
    private final RetrievalRecordRepository retrievalRecordRepository;
    private final KnowledgeEvidenceEvaluationService evidenceEvaluationService;
    private final KnowledgeQueryPlanTraceFormatter queryPlanTraceFormatter;
    private final ControlledDeepScanService controlledDeepScanService;
    private final SourceEvidenceLoopKnowledgeAdapter sourceEvidenceLoopKnowledgeAdapter;
    private final KnowledgeSourceRepository sourceRepository;

    public RagAnswerApplicationService(
            KnowledgeSearchApplicationService searchService,
            RagPromptBuilder promptBuilder,
            LlmClient llmClient,
            DevContextLlmProperties llmProperties,
            AgentRunApplicationService runService,
            RetrievalRecordRepository retrievalRecordRepository,
            KnowledgeEvidenceEvaluationService evidenceEvaluationService,
            KnowledgeQueryPlanTraceFormatter queryPlanTraceFormatter,
            ControlledDeepScanService controlledDeepScanService,
            SourceEvidenceLoopKnowledgeAdapter sourceEvidenceLoopKnowledgeAdapter,
            KnowledgeSourceRepository sourceRepository
    ) {
        this.searchService = searchService;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.runService = runService;
        this.retrievalRecordRepository = retrievalRecordRepository;
        this.evidenceEvaluationService = evidenceEvaluationService;
        this.queryPlanTraceFormatter = queryPlanTraceFormatter;
        this.controlledDeepScanService = controlledDeepScanService;
        this.sourceEvidenceLoopKnowledgeAdapter = sourceEvidenceLoopKnowledgeAdapter;
        this.sourceRepository = sourceRepository;
    }

    public RagAnswerResult ask(RagAskCommand command) {
        AgentRun run = runService.startRun(null, "KNOWLEDGE_RAG_ASK", "v0.4");
        try {
            KnowledgeSearchResponse searchResponse = searchService.search(new KnowledgeSearchCommand(
                    command.query(),
                    command.sourceId(),
                    command.topK()
            ), run.id());
            runService.recordEvent(run.id(), "KNOWLEDGE_QUERY_REWRITTEN", searchResponse.query(), searchResponse.rewrittenQuery(), "success", null, null);
            runService.recordEvent(run.id(), "KNOWLEDGE_QUERY_PLAN_BUILT", searchResponse.query(), queryPlanTraceFormatter.summary(searchResponse.queryPlan()), "success", null, null);
            boolean sourceEvidenceLoopPath = shouldUseSourceEvidenceLoop(searchResponse);
            if (sourceEvidenceLoopPath) {
                SourceEvidenceLoopKnowledgeAdapter.SourceEvidencePack evidencePack = selectSourceEvidencePack(
                        run.id(),
                        command.sourceId(),
                        searchResponse
                );
                List<KnowledgeSearchResult> primaryEvidence = toSearchResults(command.sourceId(), evidencePack);
                searchResponse = withResults(searchResponse, primaryEvidence);
            }
            runService.recordEvent(
                    run.id(),
                    "KNOWLEDGE_RETRIEVED",
                    searchResponse.rewrittenQuery(),
                    sourceEvidenceLoopPath
                            ? searchResponse.results().size() + " source evidence fragments retrieved"
                            : searchResponse.results().size() + " chunks retrieved",
                    "success",
                    null,
                    null
            );
            runService.recordEvent(run.id(), "KNOWLEDGE_EVIDENCE_RETRIEVED", searchResponse.queryPlan().requiredEvidenceTypes().toString(), evidenceDistribution(searchResponse), "success", null, null);

            EvidenceEvaluation evidenceEvaluation = evidenceEvaluationService.evaluate(searchResponse);
            runService.recordEvent(
                    run.id(),
                    "KNOWLEDGE_EVIDENCE_EVALUATED",
                    evidenceEvaluation.requiredEvidenceTypes().toString(),
                    evaluationSummary(evidenceEvaluation),
                    "success",
                    null,
                    null
            );
            if (!sourceEvidenceLoopPath && !evidenceEvaluation.sufficient()) {
                ControlledDeepScanService.Result deepScanResult = controlledDeepScanService.scan(
                        run.id(),
                        command.sourceId(),
                        searchResponse,
                        evidenceEvaluation
                );
                recordDeepScanEvents(run.id(), deepScanResult);
                if (!deepScanResult.evidenceCandidates().isEmpty()) {
                    searchResponse = withDeepScanResults(searchResponse, deepScanResult.evidenceCandidates());
                    evidenceEvaluation = evidenceEvaluationService.evaluate(searchResponse);
                    runService.recordEvent(
                            run.id(),
                            "KNOWLEDGE_DEEP_SCAN_EVIDENCE_EVALUATED",
                            deepScanResult.scannedFiles().toString(),
                            evaluationSummary(evidenceEvaluation),
                            "success",
                            null,
                            null
                    );
                }
            }
            runService.recordEvent(
                    run.id(),
                    "KNOWLEDGE_ANSWER_GUARD_APPLIED",
                    evidenceEvaluation.answerGuardDecision(),
                    evaluationSummary(evidenceEvaluation),
                    "success",
                    null,
                    null
            );
            if (!evidenceEvaluation.sufficient()) {
                String answer = evidenceEvaluation.noAnswerRequired()
                        ? evidenceEvaluationService.insufficientEvidenceAnswer(evidenceEvaluation)
                        : evidenceEvaluationService.partialEvidenceAnswer(evidenceEvaluation);
                runService.finishRun(run, 0, 0);
                return new RagAnswerResult(
                        run.id(),
                        searchResponse.retrievalRecordId(),
                        searchResponse.query(),
                        searchResponse.rewrittenQuery(),
                        searchResponse.queryPlan(),
                        evidenceEvaluation,
                        answer,
                        searchResponse.results()
                );
            }

            String prompt = promptBuilder.build(searchResponse.query(), searchResponse.rewrittenQuery(), searchResponse.queryPlan(), searchResponse.results());
            runService.recordEvent(run.id(), "PROMPT_BUILT", "knowledge RAG prompt", prompt.length() + " chars", "success", null, null);

            LlmResponse response = llmClient.chat(new LlmRequest(prompt, llmProperties.modelName()));
            runService.recordEvent(run.id(), "LLM_CALLED", llmProperties.providerModelLabel(), "LLM response generated", "success", null, null);
            String answer = sourceEvidenceLoopPath
                    ? withSourcePathContract(response.content(), searchResponse.results())
                    : response.content();
            runService.recordEvent(run.id(), "RAG_ANSWER_GENERATED", searchResponse.rewrittenQuery(), answer.length() + " chars", "success", null, null);
            runService.finishRun(run, response.inputTokenEstimate(), response.outputTokenEstimate());
            return new RagAnswerResult(
                    run.id(),
                    searchResponse.retrievalRecordId(),
                    searchResponse.query(),
                    searchResponse.rewrittenQuery(),
                    searchResponse.queryPlan(),
                    evidenceEvaluation,
                    answer,
                    searchResponse.results()
            );
        } catch (RuntimeException e) {
            runService.failRun(run, e.getMessage());
            throw e;
        }
    }

    public KnowledgeRunDetail getRunDetail(Long runId) {
        return new KnowledgeRunDetail(
                runService.getRun(runId),
                runService.listEvents(runId),
                retrievalRecordRepository.findByRunId(runId)
        );
    }

    private String evidenceDistribution(KnowledgeSearchResponse response) {
        return response.results().stream()
                .flatMap(result -> result.evidenceTypes().stream())
                .collect(Collectors.groupingBy(Enum::name, Collectors.counting()))
                .toString();
    }

    private KnowledgeSearchResponse withDeepScanResults(
            KnowledgeSearchResponse response,
            List<KnowledgeSearchResult> deepScanResults
    ) {
        List<KnowledgeSearchResult> results = new ArrayList<>();
        if (response.results() != null) {
            results.addAll(response.results());
        }
        results.addAll(deepScanResults);
        return new KnowledgeSearchResponse(
                response.retrievalRecordId(),
                response.query(),
                response.rewrittenQuery(),
                response.queryPlan(),
                results
        );
    }

    private KnowledgeSearchResponse withResults(
            KnowledgeSearchResponse response,
            List<KnowledgeSearchResult> results
    ) {
        return new KnowledgeSearchResponse(
                response.retrievalRecordId(),
                response.query(),
                response.rewrittenQuery(),
                response.queryPlan(),
                results
        );
    }

    private SourceEvidenceLoopKnowledgeAdapter.SourceEvidencePack selectSourceEvidencePack(
            Long runId,
            Long sourceId,
            KnowledgeSearchResponse searchResponse
    ) {
        KnowledgeSource source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Knowledge source not found: " + sourceId));
        Path projectRoot = Path.of(source.rootPath()).toAbsolutePath().normalize();
        runService.recordEvent(
                runId,
                "KNOWLEDGE_SOURCE_EVIDENCE_LOOP_STARTED",
                searchResponse.query(),
                "intent=" + searchResponse.queryPlan().intent()
                        + "; fallbackToLegacyRetrieval=false"
                        + "; evidencePackOnly=true"
                        + "; primarySourceOnly=true",
                "success",
                null,
                null
        );
        SourceEvidenceLoopKnowledgeAdapter.SourceEvidencePack evidencePack =
                sourceEvidenceLoopKnowledgeAdapter.selectEvidencePack(
                        projectRoot,
                        searchResponse.query(),
                        searchResponse.queryPlan().intent()
                );
        List<String> primarySourcePaths = evidencePack.primarySourcePaths();
        String summary = "intent=" + evidencePack.intent()
                + "; selectedPrimaryEvidence=" + primarySourcePaths.size()
                + "; missingEvidenceGroups=" + compactList(evidencePack.missingEvidenceGroups())
                + "; blockedLegacySources=" + compactList(evidencePack.blockedLegacySources())
                + "; fallbackToLegacyRetrieval=false"
                + "; evidencePackOnly=true"
                + "; primarySourceOnly=true";
        runService.recordEvent(
                runId,
                evidencePack.primaryEvidence().isEmpty()
                        ? "KNOWLEDGE_SOURCE_EVIDENCE_LOOP_MISSING"
                        : "KNOWLEDGE_SOURCE_EVIDENCE_LOOP_SUPPORTED",
                compactList(primarySourcePaths),
                summary,
                "success",
                null,
                null
        );
        return evidencePack;
    }

    private boolean shouldUseSourceEvidenceLoop(KnowledgeSearchResponse searchResponse) {
        String intent = searchResponse.queryPlan().intent();
        return "implementation_detail".equals(intent)
                || "database_detail".equals(intent)
                || "configuration_detail".equals(intent)
                || "review_context_detail".equals(intent);
    }

    private List<KnowledgeSearchResult> toSearchResults(
            Long sourceId,
            SourceEvidenceLoopKnowledgeAdapter.SourceEvidencePack evidencePack
    ) {
        List<EvidenceFragment> primaryEvidence = evidencePack.primaryEvidence().stream()
                .filter(fragment -> !legacyPrimaryPath(fragment.path()))
                .toList();
        if (primaryEvidence.isEmpty()) {
            primaryEvidence = evidencePack.primaryEvidence();
        }
        List<KnowledgeSearchResult> results = new ArrayList<>();
        for (int i = 0; i < primaryEvidence.size(); i++) {
            EvidenceFragment fragment = primaryEvidence.get(i);
            double score = Math.max(0.1, 1.0 - (i * 0.01));
            results.add(new KnowledgeSearchResult(
                    null,
                    null,
                    sourceId,
                    "source-evidence-loop",
                    fragment.path(),
                    fragment.symbolOrBlock(),
                    "source-evidence-loop/" + safeGroup(fragment.evidenceGroup()),
                    primaryEvidenceContent(fragment.content()),
                    0,
                    0,
                    score,
                    evidenceTypes(fragment),
                    scoreReasons(fragment)
            ));
        }
        return List.copyOf(results);
    }

    private List<KnowledgeEvidenceType> evidenceTypes(EvidenceFragment fragment) {
        LinkedHashSet<KnowledgeEvidenceType> types = new LinkedHashSet<>();
        String group = normalize(fragment.evidenceGroup());
        String path = normalizePath(fragment.path());
        if (path.endsWith(".sql") || group.contains("schema")) {
            types.add(KnowledgeEvidenceType.SQL_SCHEMA);
        }
        if (path.endsWith(".xml") || group.contains("mapper") || group.contains("repository_sql")) {
            types.add(KnowledgeEvidenceType.MAPPER);
        }
        if (isTestPath(path) || group.startsWith("test_") || group.contains("test_or")) {
            types.add(KnowledgeEvidenceType.TEST);
        }
        if (group.contains("controller") || group.contains("endpoint")) {
            types.add(KnowledgeEvidenceType.API_CONTROLLER);
        }
        if (group.contains("properties") || group.contains("config")) {
            types.add(KnowledgeEvidenceType.CONFIG);
        }
        if (path.endsWith(".yml") || path.endsWith(".yaml") || path.endsWith(".properties")) {
            types.add(KnowledgeEvidenceType.CONFIG);
        }
        if (path.contains("docker") || group.contains("deployment")) {
            types.add(KnowledgeEvidenceType.DEPLOYMENT);
        }
        if (path.endsWith(".java") || path.endsWith(".ts") || path.endsWith(".tsx")
                || path.endsWith(".js") || path.endsWith(".py")) {
            types.add(KnowledgeEvidenceType.SERVICE_CODE);
        }
        if (types.isEmpty()) {
            types.add(KnowledgeEvidenceType.SERVICE_CODE);
        }
        return types.stream().toList();
    }

    private List<String> scoreReasons(EvidenceFragment fragment) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        reasons.add("source_evidence_loop");
        reasons.add("evidence_pack_only");
        reasons.add("primary_source_only");
        reasons.add("evidence_group:" + safeGroup(fragment.evidenceGroup()));
        reasons.add("source_path:" + normalizePath(fragment.path()));
        if (fragment.startLine() > 0 && fragment.endLine() >= fragment.startLine()) {
            reasons.add("line_range:" + fragment.startLine() + "-" + fragment.endLine());
        }
        return reasons.stream().toList();
    }

    private String withSourcePathContract(String answer, List<KnowledgeSearchResult> citations) {
        LinkedHashSet<String> paths = citations.stream()
                .map(KnowledgeSearchResult::filePath)
                .filter(path -> path != null && !path.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (paths.isEmpty()) {
            return answer;
        }
        String contractedAnswer = stripLegacySourceMarkers(stripUnsupportedInsufficiencyStatements(
                stripUnsupportedSourcePaths(answer, paths)
        ));
        StringBuilder result = new StringBuilder(contractedAnswer.stripTrailing());
        result.append("\n\nSource evidence pack paths:\n");
        int index = 1;
        for (String path : paths) {
            result.append("- ").append(path).append(" [S").append(index++).append("]\n");
        }
        return result.toString().stripTrailing();
    }

    private String stripUnsupportedSourcePaths(String answer, LinkedHashSet<String> selectedPaths) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        LinkedHashSet<String> normalizedSelected = selectedPaths.stream()
                .map(this::normalizePath)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Matcher matcher = ANSWER_SOURCE_PATH_PATTERN.matcher(answer);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            String matchedPath = matcher.group();
            String replacement = normalizedSelected.contains(normalizePath(matchedPath))
                    ? matchedPath
                    : fileName(matchedPath);
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sanitized);
        return sanitized.toString();
    }

    private String stripUnsupportedInsufficiencyStatements(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : answer.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (line.isBlank()) {
                appendSupportedParagraph(paragraphs, current);
                current.setLength(0);
                continue;
            }
            if (!current.isEmpty()) {
                current.append("\n");
            }
            current.append(line);
        }
        appendSupportedParagraph(paragraphs, current);
        if (paragraphs.isEmpty()) {
            return answer.strip();
        }
        return String.join("\n\n", paragraphs).strip();
    }

    private String stripLegacySourceMarkers(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        return answer.lines()
                .filter(line -> !legacySourceMarker(line))
                .collect(Collectors.joining("\n"))
                .strip();
    }

    private void appendSupportedParagraph(List<String> paragraphs, StringBuilder paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        String value = paragraph.toString().strip();
        if (!unsupportedInsufficiencyParagraph(value)) {
            paragraphs.add(value);
        }
    }

    private boolean unsupportedInsufficiencyParagraph(String paragraph) {
        String lower = normalize(paragraph);
        if (containsAny(
                lower,
                "i can only partially answer",
                "i can provide a partial answer",
                "i can partially answer",
                "what is missing:",
                "missing evidence statement",
                "missing evidence required",
                "missing evidence needed",
                "key implementation details are missing",
                "lacks the core implementation logic",
                "incomplete evidence",
                "limited evidence",
                "not enough evidence",
                "cannot answer",
                "to fully answer",
                "what should be indexed next",
                "should be indexed next",
                "need to index",
                "you would need to index",
                "\u5f53\u524d\u8bc1\u636e\u4e0d\u8db3",
                "\u5f53\u524d\u8bc1\u636e\u4e0d\u5b8c\u6574",
                "\u8bc1\u636e\u4e0d\u8db3",
                "\u8bc1\u636e\u4e0d\u5b8c\u6574",
                "\u7f3a\u5c11\u8bc1\u636e",
                "\u7f3a\u5c11\u7684\u8bc1\u636e",
                "\u65e0\u6cd5\u56de\u7b54",
                "\u5f53\u524d\u4e0a\u4e0b\u6587\u7f3a\u5c11",
                "\u5f53\u524d\u4e0a\u4e0b\u6587\u672a\u63d0\u4f9b",
                "\u672a\u63d0\u4f9b",
                "\u7f3a\u5931\u7684\u6df1\u5c42\u5b9e\u73b0\u8bc1\u636e",
                "\u5efa\u8bae\u7d22\u5f15",
                "\u9700\u8981\u8865\u5145\u7d22\u5f15",
                "\u5982\u9700\u6df1\u5165"
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
                    "\u5f53\u524d\u4e0a\u4e0b\u6587",
                    "\u5f53\u524d\u8bc1\u636e"
            );
            boolean insufficientLine = containsAny(
                    line,
                    "missing",
                    "limited",
                    "not shown",
                    "not present",
                    "not included",
                    "not provided",
                    "does not contain",
                    "does **not** contain",
                    "not contain",
                    "does not show",
                    "lacks",
                    "\u4e0d\u8db3",
                    "\u4e0d\u5b8c\u6574",
                    "\u7f3a\u5c11",
                    "\u7f3a\u5931",
                    "\u672a\u63d0\u4f9b",
                    "\u65e0\u6cd5"
            );
            if (currentEvidenceLine && insufficientLine) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String value, String... needles) {
        String safeValue = value == null ? "" : value;
        for (String needle : needles) {
            if (safeValue.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String fileName(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private boolean legacyPrimaryPath(String path) {
        String normalized = normalizePath(path);
        return normalized.startsWith("docs/")
                || normalized.contains("/docs/")
                || normalized.startsWith(".ai/generated/")
                || normalized.startsWith(".ai/manual/")
                || normalized.endsWith("/readme.md")
                || normalized.equals("readme.md")
                || normalized.endsWith(".md");
    }

    private boolean legacySourceMarker(String value) {
        String normalized = normalizePath(value);
        return normalized.contains(".ai/generated")
                || normalized.contains(".ai/manual")
                || normalized.contains("readme")
                || normalized.contains("docs/")
                || normalized.contains("vector chunk")
                || normalized.contains("legacy retrieval");
    }

    private String primaryEvidenceContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content.lines()
                .filter(line -> {
                    return !legacySourceMarker(line);
                })
                .collect(Collectors.joining("\n"));
    }

    private boolean isTestPath(String path) {
        String normalized = normalizePath(path);
        return normalized.startsWith("src/test/") || normalized.contains("/src/test/");
    }

    private String safeGroup(String value) {
        String normalized = normalize(value).replaceAll("[^a-z0-9_./-]+", "_");
        return normalized.isBlank() ? "primary_source" : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String normalizePath(String value) {
        return normalize(value).replace('\\', '/');
    }

    private void recordDeepScanEvents(Long runId, ControlledDeepScanService.Result result) {
        if (result.started()) {
            runService.recordEvent(
                    runId,
                    "KNOWLEDGE_DEEP_SCAN_STARTED",
                    "controlled deep scan",
                    "candidateFiles=" + compactList(result.candidateFiles())
                            + "; budget=files:" + result.fileBudget()
                            + ",chars:" + result.charBudget()
                            + ",lines:" + result.lineBudget(),
                    "success",
                    null,
                    null
            );
            runService.recordEvent(
                    runId,
                    "KNOWLEDGE_DEEP_SCAN_FINISHED",
                    compactList(result.scannedFiles()),
                    "evidenceCandidates=" + result.evidenceCandidates().size()
                            + "; filesRead=" + result.filesRead()
                            + "; charsRead=" + result.charsRead()
                            + "; linesRead=" + result.linesRead()
                            + "; budgetLimited=" + result.budgetLimited()
                            + "; skippedReasons=" + compactList(result.skippedReasons()),
                    "success",
                    null,
                    null
            );
            return;
        }
        runService.recordEvent(
                runId,
                "KNOWLEDGE_DEEP_SCAN_SKIPPED",
                "controlled deep scan",
                "reason=" + result.skipReason()
                        + "; skippedReasons=" + compactList(result.skippedReasons())
                        + "; budget=files:" + result.fileBudget()
                        + ",chars:" + result.charBudget()
                        + ",lines:" + result.lineBudget(),
                "success",
                null,
                null
        );
    }

    private String compactList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        int limit = Math.min(values.size(), 6);
        List<String> compact = values.subList(0, limit);
        if (values.size() <= limit) {
            return compact.toString();
        }
        return compact + "...+" + (values.size() - limit);
    }

    private String evaluationSummary(EvidenceEvaluation evaluation) {
        return evaluation.status()
                + "; guard=" + evaluation.answerGuardDecision()
                + "; matchedRequired=" + evaluation.matchedRequiredEvidenceTypes()
                + "; missingRequired=" + evaluation.missingRequiredEvidenceTypes()
                + "; matchedPreferred=" + evaluation.matchedPreferredEvidenceTypes()
                + "; weakEvidence=" + evaluation.weakEvidenceTypes();
    }
}
