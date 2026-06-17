package com.devcontext.application.context;

import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.domain.context.ReadOnlyContextProviderTrace;
import org.springframework.stereotype.Component;

@Component
public class AgentRunReadOnlyContextTraceRecorder implements ReadOnlyContextTraceRecorder {

    private final AgentRunApplicationService runService;

    public AgentRunReadOnlyContextTraceRecorder(AgentRunApplicationService runService) {
        this.runService = runService;
    }

    @Override
    public void record(ReadOnlyContextProviderTrace trace) {
        if (trace == null || trace.runId() == null) {
            return;
        }
        runService.recordEvent(
                trace.runId(),
                "READ_ONLY_CONTEXT_PROVIDER_" + trace.status().toUpperCase(),
                trace.operation() + ":" + trace.subject(),
                summary(trace),
                "success",
                null,
                null
        );
    }

    private String summary(ReadOnlyContextProviderTrace trace) {
        return "provider=" + trace.providerName()
                + "; status=" + trace.status()
                + "; reason=" + trace.reason()
                + "; files=" + trace.files()
                + "; matchesReturned=" + trace.matchesReturned()
                + "; filesRead=" + trace.filesRead()
                + "; charsReturned=" + trace.charactersReturned()
                + "; linesReturned=" + trace.linesReturned()
                + "; budgetLimited=" + trace.budgetLimited()
                + "; budget=matches:" + trace.matchBudget()
                + ",files:" + trace.fileBudget()
                + ",chars:" + trace.characterBudget()
                + ",lines:" + trace.lineBudget();
    }
}
