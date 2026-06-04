package com.devcontext.adapters.web;

import com.devcontext.application.decision.CreateDecisionCommand;
import com.devcontext.application.decision.BatchUpdateDecisionStatusCommand;
import com.devcontext.application.decision.DecisionDuplicateCandidatesCommand;
import com.devcontext.application.decision.DecisionListCommand;
import com.devcontext.application.decision.DecisionMemoryApplicationService;
import com.devcontext.application.decision.DecisionRecallEvaluationApplicationService;
import com.devcontext.application.decision.DecisionRecallEvaluationCaseCommand;
import com.devcontext.application.decision.DecisionRecallEvaluationCommand;
import com.devcontext.application.decision.DecisionReuseAdviceCommand;
import com.devcontext.application.decision.DecisionSearchCommand;
import com.devcontext.application.decision.RebuildDecisionEmbeddingsCommand;
import com.devcontext.application.decision.UpdateDecisionReuseFeedbackCommand;
import com.devcontext.application.decision.UpdateDecisionStatusCommand;
import com.devcontext.common.api.ApiResponse;
import com.devcontext.domain.decision.DecisionBatchStatusUpdateResult;
import com.devcontext.domain.decision.DecisionCard;
import com.devcontext.domain.decision.DecisionCreateResult;
import com.devcontext.domain.decision.DecisionDuplicateCandidatesResponse;
import com.devcontext.domain.decision.DecisionEmbeddingRebuildResult;
import com.devcontext.domain.decision.DecisionEvidence;
import com.devcontext.domain.decision.DecisionRecallEvaluationResult;
import com.devcontext.domain.decision.DecisionReuseAdviceResult;
import com.devcontext.domain.decision.DecisionReuseRecord;
import com.devcontext.domain.decision.DecisionSearchResponse;
import com.devcontext.domain.decision.DecisionStatusUpdateResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DecisionController {

    private final DecisionMemoryApplicationService decisionMemoryService;
    private final DecisionRecallEvaluationApplicationService recallEvaluationService;

    public DecisionController(
            DecisionMemoryApplicationService decisionMemoryService,
            DecisionRecallEvaluationApplicationService recallEvaluationService
    ) {
        this.decisionMemoryService = decisionMemoryService;
        this.recallEvaluationService = recallEvaluationService;
    }

    @PostMapping("/api/decisions")
    public ApiResponse<DecisionCreateResult> createDecision(@RequestBody CreateDecisionRequest request) {
        return ApiResponse.ok(decisionMemoryService.createDecision(new CreateDecisionCommand(
                request.projectId(),
                request.title(),
                request.scenario(),
                request.options(),
                request.decision(),
                request.reasons(),
                request.tradeOffs(),
                request.applicableWhen(),
                request.notApplicableWhen(),
                request.outcome(),
                request.evidence(),
                request.status(),
                request.tags()
        )));
    }

    @GetMapping("/api/decisions")
    public ApiResponse<List<DecisionCard>> listDecisions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String query
    ) {
        return ApiResponse.ok(decisionMemoryService.listDecisions(new DecisionListCommand(status, projectId, tag, query)));
    }

    @GetMapping("/api/decisions/duplicate-candidates")
    public ApiResponse<DecisionDuplicateCandidatesResponse> duplicateCandidates(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Double minScore
    ) {
        return ApiResponse.ok(decisionMemoryService.duplicateCandidates(new DecisionDuplicateCandidatesCommand(
                status,
                projectId,
                tag,
                query,
                minScore
        )));
    }

    @GetMapping("/api/decisions/{decisionId}")
    public ApiResponse<DecisionCard> getDecision(@PathVariable Long decisionId) {
        return ApiResponse.ok(decisionMemoryService.getDecision(decisionId));
    }

    @PostMapping("/api/decisions/{decisionId}/embedding")
    public ApiResponse<DecisionCard> rebuildEmbedding(@PathVariable Long decisionId) {
        return ApiResponse.ok(decisionMemoryService.rebuildEmbedding(decisionId));
    }

    @PostMapping("/api/decisions/embeddings/rebuild")
    public ApiResponse<DecisionEmbeddingRebuildResult> rebuildEmbeddings(@RequestBody DecisionEmbeddingRebuildRequest request) {
        return ApiResponse.ok(decisionMemoryService.rebuildDecisionEmbeddings(new RebuildDecisionEmbeddingsCommand(
                request.status(),
                request.projectId(),
                request.tag(),
                request.query()
        )));
    }

    @PatchMapping("/api/decisions/{decisionId}/status")
    public ApiResponse<DecisionStatusUpdateResult> updateDecisionStatus(
            @PathVariable Long decisionId,
            @RequestBody DecisionStatusUpdateRequest request
    ) {
        return ApiResponse.ok(decisionMemoryService.updateDecisionStatus(new UpdateDecisionStatusCommand(
                decisionId,
                request.status()
        )));
    }

    @PatchMapping("/api/decisions/batch-status")
    public ApiResponse<DecisionBatchStatusUpdateResult> batchUpdateDecisionStatus(@RequestBody BatchDecisionStatusUpdateRequest request) {
        return ApiResponse.ok(decisionMemoryService.batchUpdateDecisionStatus(new BatchUpdateDecisionStatusCommand(
                request.decisionIds(),
                request.status()
        )));
    }

    @PostMapping("/api/decisions/search")
    public ApiResponse<DecisionSearchResponse> search(@RequestBody DecisionSearchRequest request) {
        return ApiResponse.ok(decisionMemoryService.search(new DecisionSearchCommand(
                request.query(),
                request.projectId(),
                request.tags(),
                request.topK()
        )));
    }

    @PostMapping("/api/decisions/reuse-advice")
    public ApiResponse<DecisionReuseAdviceResult> reuseAdvice(@RequestBody DecisionReuseAdviceRequest request) {
        return ApiResponse.ok(decisionMemoryService.reuseAdvice(new DecisionReuseAdviceCommand(
                request.query(),
                request.projectId(),
                request.tags(),
                request.topK()
        )));
    }

    @PostMapping("/api/decisions/recall-evaluations")
    public ApiResponse<DecisionRecallEvaluationResult> evaluateRecall(@RequestBody DecisionRecallEvaluationRequest request) {
        List<DecisionRecallEvaluationCaseCommand> cases = request.cases() == null
                ? List.of()
                : request.cases().stream()
                        .map(item -> new DecisionRecallEvaluationCaseCommand(
                                item.name(),
                                item.query(),
                                item.projectId(),
                                item.tags(),
                                item.topK(),
                                item.expectedDecisionIds(),
                                item.forbiddenDecisionIds()
                        ))
                        .toList();
        return ApiResponse.ok(recallEvaluationService.evaluate(new DecisionRecallEvaluationCommand(cases)));
    }

    @PatchMapping("/api/decision-reuse-records/{recordId}/feedback")
    public ApiResponse<DecisionReuseRecord> updateReuseFeedback(
            @PathVariable Long recordId,
            @RequestBody DecisionReuseFeedbackRequest request
    ) {
        return ApiResponse.ok(decisionMemoryService.updateReuseFeedback(new UpdateDecisionReuseFeedbackCommand(
                recordId,
                request.status(),
                request.accepted(),
                request.userFeedback()
        )));
    }

    public record CreateDecisionRequest(
            Long projectId,
            String title,
            String scenario,
            List<String> options,
            String decision,
            List<String> reasons,
            List<String> tradeOffs,
            List<String> applicableWhen,
            List<String> notApplicableWhen,
            String outcome,
            List<DecisionEvidence> evidence,
            String status,
            List<String> tags
    ) {
    }

    public record DecisionSearchRequest(
            String query,
            Long projectId,
            List<String> tags,
            Integer topK
    ) {
    }

    public record DecisionReuseAdviceRequest(
            String query,
            Long projectId,
            List<String> tags,
            Integer topK
    ) {
    }

    public record DecisionRecallEvaluationRequest(
            List<DecisionRecallEvaluationCaseRequest> cases
    ) {
    }

    public record DecisionRecallEvaluationCaseRequest(
            String name,
            String query,
            Long projectId,
            List<String> tags,
            Integer topK,
            List<Long> expectedDecisionIds,
            List<Long> forbiddenDecisionIds
    ) {
    }

    public record DecisionReuseFeedbackRequest(
            String status,
            Boolean accepted,
            String userFeedback
    ) {
    }

    public record DecisionStatusUpdateRequest(
            String status
    ) {
    }

    public record BatchDecisionStatusUpdateRequest(
            List<Long> decisionIds,
            String status
    ) {
    }

    public record DecisionEmbeddingRebuildRequest(
            String status,
            Long projectId,
            String tag,
            String query
    ) {
    }
}
