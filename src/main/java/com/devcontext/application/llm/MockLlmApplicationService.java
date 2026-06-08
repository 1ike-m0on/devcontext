package com.devcontext.application.llm;

import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.domain.run.AgentRun;
import com.devcontext.ports.llm.LlmClient;
import org.springframework.stereotype.Service;

@Service
public class MockLlmApplicationService {

    private final LlmClient llmClient;
    private final AgentRunApplicationService runService;
    private final DevContextLlmProperties llmProperties;

    public MockLlmApplicationService(LlmClient llmClient, AgentRunApplicationService runService, DevContextLlmProperties llmProperties) {
        this.llmClient = llmClient;
        this.runService = runService;
        this.llmProperties = llmProperties;
    }

    public MockLlmResult chat(Long projectId, String prompt) {
        String modelName = llmProperties.modelName();
        AgentRun run = runService.startRun(projectId, "LLM_TEST", "mvp0");
        runService.recordEvent(run.id(), "PROMPT_BUILT", "llm prompt", "Prompt accepted", "success", null, null);
        LlmResponse response = llmClient.chat(new LlmRequest(prompt, modelName));
        runService.recordEvent(run.id(), "LLM_CALLED", llmProperties.providerModelLabel(), "LLM response generated", "success", null, null);
        AgentRun finished = runService.finishRun(run, response.inputTokenEstimate(), response.outputTokenEstimate());
        return new MockLlmResult(finished.id(), response);
    }

    public record MockLlmResult(Long runId, LlmResponse response) {
    }
}
