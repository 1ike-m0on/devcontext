package com.devcontext.application.llm;

import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.domain.run.AgentRun;
import com.devcontext.ports.llm.LlmClient;
import org.springframework.stereotype.Service;

@Service
public class MockLlmApplicationService {

    private final LlmClient llmClient;
    private final AgentRunApplicationService runService;

    public MockLlmApplicationService(LlmClient llmClient, AgentRunApplicationService runService) {
        this.llmClient = llmClient;
        this.runService = runService;
    }

    public MockLlmResult chat(Long projectId, String prompt) {
        AgentRun run = runService.startRun(projectId, "LLM_TEST", "mock-llm", "mvp0");
        runService.recordEvent(run.id(), "PROMPT_BUILT", "mock prompt", "Prompt accepted", "success", null, null);
        LlmResponse response = llmClient.chat(new LlmRequest(prompt, "mock-llm"));
        runService.recordEvent(run.id(), "LLM_CALLED", "mock-llm", "Mock response generated", "success", null, null);
        AgentRun finished = runService.finishRun(run, response.inputTokenEstimate(), response.outputTokenEstimate());
        return new MockLlmResult(finished.id(), response);
    }

    public record MockLlmResult(Long runId, LlmResponse response) {
    }
}

