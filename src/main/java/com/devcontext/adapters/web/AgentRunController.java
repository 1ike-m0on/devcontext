package com.devcontext.adapters.web;

import com.devcontext.application.llm.MockLlmApplicationService;
import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.common.api.ApiResponse;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.domain.run.AgentEvent;
import com.devcontext.domain.run.AgentRun;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent-runs")
public class AgentRunController {

    private final AgentRunApplicationService runService;
    private final MockLlmApplicationService mockLlmService;

    public AgentRunController(AgentRunApplicationService runService, MockLlmApplicationService mockLlmService) {
        this.runService = runService;
        this.mockLlmService = mockLlmService;
    }

    @PostMapping("/mock-llm")
    public ApiResponse<MockLlmResponse> callMockLlm(@Valid @RequestBody MockLlmRequest request) {
        MockLlmApplicationService.MockLlmResult result = mockLlmService.chat(request.projectId(), request.prompt());
        return ApiResponse.ok(new MockLlmResponse(result.runId(), result.response()));
    }

    @GetMapping("/{runId}")
    public ApiResponse<AgentRun> getRun(@PathVariable Long runId) {
        return ApiResponse.ok(runService.getRun(runId));
    }

    @GetMapping("/{runId}/events")
    public ApiResponse<List<AgentEvent>> listEvents(@PathVariable Long runId) {
        return ApiResponse.ok(runService.listEvents(runId));
    }

    public record MockLlmRequest(
            Long projectId,
            @NotBlank String prompt
    ) {
    }

    public record MockLlmResponse(
            Long runId,
            LlmResponse response
    ) {
    }
}

