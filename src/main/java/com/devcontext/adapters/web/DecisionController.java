package com.devcontext.adapters.web;

import com.devcontext.application.decision.CreateDecisionCommand;
import com.devcontext.application.decision.DecisionMemoryApplicationService;
import com.devcontext.application.decision.DecisionReuseAdviceCommand;
import com.devcontext.application.decision.DecisionSearchCommand;
import com.devcontext.common.api.ApiResponse;
import com.devcontext.domain.decision.DecisionCard;
import com.devcontext.domain.decision.DecisionCreateResult;
import com.devcontext.domain.decision.DecisionEvidence;
import com.devcontext.domain.decision.DecisionReuseAdviceResult;
import com.devcontext.domain.decision.DecisionSearchResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DecisionController {

    private final DecisionMemoryApplicationService decisionMemoryService;

    public DecisionController(DecisionMemoryApplicationService decisionMemoryService) {
        this.decisionMemoryService = decisionMemoryService;
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

    @GetMapping("/api/decisions/{decisionId}")
    public ApiResponse<DecisionCard> getDecision(@PathVariable Long decisionId) {
        return ApiResponse.ok(decisionMemoryService.getDecision(decisionId));
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
}
