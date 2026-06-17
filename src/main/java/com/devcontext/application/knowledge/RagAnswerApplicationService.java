package com.devcontext.application.knowledge;

import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.knowledge.EvidenceEvaluation;
import com.devcontext.domain.knowledge.KnowledgeRunDetail;
import com.devcontext.domain.knowledge.KnowledgeSearchResponse;
import com.devcontext.domain.knowledge.KnowledgeSearchResult;
import com.devcontext.domain.knowledge.RagAnswerResult;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.domain.run.AgentRun;
import com.devcontext.ports.knowledge.RetrievalRecordRepository;
import com.devcontext.ports.llm.LlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RagAnswerApplicationService {

    private final KnowledgeSearchApplicationService searchService;
    private final RagPromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final DevContextLlmProperties llmProperties;
    private final AgentRunApplicationService runService;
    private final RetrievalRecordRepository retrievalRecordRepository;
    private final KnowledgeEvidenceEvaluationService evidenceEvaluationService;
    private final KnowledgeQueryPlanTraceFormatter queryPlanTraceFormatter;
    private final ControlledDeepScanService controlledDeepScanService;

    public RagAnswerApplicationService(
            KnowledgeSearchApplicationService searchService,
            RagPromptBuilder promptBuilder,
            LlmClient llmClient,
            DevContextLlmProperties llmProperties,
            AgentRunApplicationService runService,
            RetrievalRecordRepository retrievalRecordRepository,
            KnowledgeEvidenceEvaluationService evidenceEvaluationService,
            KnowledgeQueryPlanTraceFormatter queryPlanTraceFormatter,
            ControlledDeepScanService controlledDeepScanService
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
            runService.recordEvent(run.id(), "KNOWLEDGE_RETRIEVED", searchResponse.rewrittenQuery(), searchResponse.results().size() + " chunks retrieved", "success", null, null);
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
            if (!evidenceEvaluation.sufficient()) {
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
            runService.recordEvent(run.id(), "RAG_ANSWER_GENERATED", searchResponse.rewrittenQuery(), response.content().length() + " chars", "success", null, null);
            runService.finishRun(run, response.inputTokenEstimate(), response.outputTokenEstimate());
            return new RagAnswerResult(
                    run.id(),
                    searchResponse.retrievalRecordId(),
                    searchResponse.query(),
                    searchResponse.rewrittenQuery(),
                    searchResponse.queryPlan(),
                    evidenceEvaluation,
                    response.content(),
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
