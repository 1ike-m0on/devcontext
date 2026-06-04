package com.devcontext.application.decision;

import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.common.error.ApiException;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.decision.DecisionRecallEvaluationCaseResult;
import com.devcontext.domain.decision.DecisionRecallEvaluationResult;
import com.devcontext.domain.decision.DecisionSearchResponse;
import com.devcontext.domain.decision.DecisionSearchResult;
import com.devcontext.domain.run.AgentRun;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DecisionRecallEvaluationApplicationService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;
    private static final int MAX_CASES = 50;

    private final DecisionSearchService decisionSearchService;
    private final AgentRunApplicationService runService;
    private final DevContextLlmProperties llmProperties;

    public DecisionRecallEvaluationApplicationService(
            DecisionSearchService decisionSearchService,
            AgentRunApplicationService runService,
            DevContextLlmProperties llmProperties
    ) {
        this.decisionSearchService = decisionSearchService;
        this.runService = runService;
        this.llmProperties = llmProperties;
    }

    public DecisionRecallEvaluationResult evaluate(DecisionRecallEvaluationCommand command) {
        validate(command);
        AgentRun run = runService.startRun(null, "DECISION_RECALL_EVALUATION", llmProperties.modelName(), "v0.5.3");
        try {
            List<DecisionRecallEvaluationCaseResult> results = new ArrayList<>();
            for (DecisionRecallEvaluationCaseCommand evaluationCase : command.cases()) {
                DecisionRecallEvaluationCaseResult result = evaluateCase(evaluationCase);
                results.add(result);
                runService.recordEvent(
                        run.id(),
                        "DECISION_RECALL_CASE_EVALUATED",
                        caseLabel(evaluationCase),
                        eventSummary(result),
                        "success",
                        null,
                        null
                );
            }
            DecisionRecallEvaluationResult result = summarize(run.id(), results);
            runService.recordEvent(
                    run.id(),
                    "DECISION_RECALL_EVALUATION_SUMMARIZED",
                    result.caseCount() + " cases",
                    "hitRate=" + result.hitRate() + ", mrr=" + result.meanReciprocalRank(),
                    "success",
                    null,
                    null
            );
            runService.finishRun(run, 0, 0);
            return result;
        } catch (RuntimeException e) {
            runService.failRun(run, e.getMessage());
            throw e;
        }
    }

    private DecisionRecallEvaluationCaseResult evaluateCase(DecisionRecallEvaluationCaseCommand evaluationCase) {
        String query = requireText(evaluationCase.query(), "query");
        int topK = normalizeTopK(evaluationCase.topK());
        List<Long> expectedDecisionIds = distinctPositiveIds(evaluationCase.expectedDecisionIds());
        List<Long> forbiddenDecisionIds = distinctPositiveIds(evaluationCase.forbiddenDecisionIds());
        if (expectedDecisionIds.isEmpty()) {
            throw new ApiException("DECISION_RECALL_EXPECTED_IDS_REQUIRED", "expectedDecisionIds is required", HttpStatus.BAD_REQUEST);
        }

        DecisionSearchResponse response = decisionSearchService.search(new DecisionSearchCommand(
                query,
                evaluationCase.projectId(),
                evaluationCase.tags(),
                topK
        ));
        List<DecisionSearchResult> matches = response.matches();
        List<Long> returnedDecisionIds = matches.stream()
                .map(match -> match.decision().id())
                .toList();
        Set<Long> expectedSet = new LinkedHashSet<>(expectedDecisionIds);
        List<Long> hitDecisionIds = returnedDecisionIds.stream()
                .filter(expectedSet::contains)
                .distinct()
                .toList();
        List<Long> missingExpectedDecisionIds = expectedDecisionIds.stream()
                .filter(id -> !hitDecisionIds.contains(id))
                .toList();
        List<Long> unexpectedDecisionIds = returnedDecisionIds.stream()
                .filter(id -> !expectedSet.contains(id))
                .distinct()
                .toList();
        Set<Long> forbiddenSet = new LinkedHashSet<>(forbiddenDecisionIds);
        List<Long> forbiddenHitDecisionIds = returnedDecisionIds.stream()
                .filter(forbiddenSet::contains)
                .distinct()
                .toList();
        Integer firstHitRank = firstHitRank(returnedDecisionIds, expectedSet);
        boolean hit = firstHitRank != null;
        boolean forbiddenPass = forbiddenHitDecisionIds.isEmpty();
        double reciprocalRank = hit ? round(1.0 / firstHitRank) : 0;
        double precisionAtK = round((double) hitDecisionIds.size() / topK);
        double recallAtK = round((double) hitDecisionIds.size() / expectedDecisionIds.size());
        double falsePositiveAtK = round((double) unexpectedDecisionIds.size() / topK);

        return new DecisionRecallEvaluationCaseResult(
                evaluationCase.name(),
                query,
                topK,
                expectedDecisionIds,
                returnedDecisionIds,
                hitDecisionIds,
                missingExpectedDecisionIds,
                unexpectedDecisionIds,
                forbiddenDecisionIds,
                forbiddenHitDecisionIds,
                hit,
                forbiddenPass,
                firstHitRank,
                reciprocalRank,
                precisionAtK,
                recallAtK,
                falsePositiveAtK,
                matches
        );
    }

    private DecisionRecallEvaluationResult summarize(Long runId, List<DecisionRecallEvaluationCaseResult> results) {
        int caseCount = results.size();
        int hitCount = (int) results.stream().filter(DecisionRecallEvaluationCaseResult::hit).count();
        double hitRate = caseCount == 0 ? 0 : round((double) hitCount / caseCount);
        double mrr = average(results.stream().mapToDouble(DecisionRecallEvaluationCaseResult::reciprocalRank).toArray());
        double precision = average(results.stream().mapToDouble(DecisionRecallEvaluationCaseResult::precisionAtK).toArray());
        double recall = average(results.stream().mapToDouble(DecisionRecallEvaluationCaseResult::recallAtK).toArray());
        double falsePositive = average(results.stream().mapToDouble(DecisionRecallEvaluationCaseResult::falsePositiveAtK).toArray());
        double forbiddenPassRate = caseCount == 0
                ? 0
                : round((double) results.stream().filter(DecisionRecallEvaluationCaseResult::forbiddenPass).count() / caseCount);
        return new DecisionRecallEvaluationResult(
                runId,
                caseCount,
                hitCount,
                hitRate,
                mrr,
                precision,
                recall,
                falsePositive,
                forbiddenPassRate,
                results
        );
    }

    private void validate(DecisionRecallEvaluationCommand command) {
        if (command == null || command.cases() == null || command.cases().isEmpty()) {
            throw new ApiException("DECISION_RECALL_CASES_REQUIRED", "cases is required", HttpStatus.BAD_REQUEST);
        }
        if (command.cases().size() > MAX_CASES) {
            throw new ApiException("DECISION_RECALL_CASES_TOO_MANY", "cases must be <= " + MAX_CASES, HttpStatus.BAD_REQUEST);
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ApiException("DECISION_RECALL_FIELD_REQUIRED", fieldName + " is required", HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    private List<Long> distinctPositiveIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .filter(id -> id != null && id > 0)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private Integer firstHitRank(List<Long> returnedDecisionIds, Set<Long> expectedDecisionIds) {
        for (int i = 0; i < returnedDecisionIds.size(); i++) {
            if (expectedDecisionIds.contains(returnedDecisionIds.get(i))) {
                return i + 1;
            }
        }
        return null;
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private String caseLabel(DecisionRecallEvaluationCaseCommand evaluationCase) {
        if (evaluationCase.name() != null && !evaluationCase.name().isBlank()) {
            return evaluationCase.name().trim();
        }
        return requireText(evaluationCase.query(), "query");
    }

    private String eventSummary(DecisionRecallEvaluationCaseResult result) {
        return "hit=" + result.hit()
                + ", rank=" + (result.firstHitRank() == null ? "none" : result.firstHitRank())
                + ", precisionAtK=" + result.precisionAtK()
                + ", recallAtK=" + result.recallAtK()
                + ", falsePositiveAtK=" + result.falsePositiveAtK()
                + ", forbiddenPass=" + result.forbiddenPass();
    }

    private double average(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return round(sum / values.length);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
