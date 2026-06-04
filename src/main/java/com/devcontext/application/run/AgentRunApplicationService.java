package com.devcontext.application.run;

import com.devcontext.common.error.ApiException;
import com.devcontext.domain.run.AgentEvent;
import com.devcontext.domain.run.AgentRun;
import com.devcontext.ports.run.AgentEventRepository;
import com.devcontext.ports.run.AgentRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AgentRunApplicationService {

    private final AgentRunRepository runRepository;
    private final AgentEventRepository eventRepository;

    public AgentRunApplicationService(AgentRunRepository runRepository, AgentEventRepository eventRepository) {
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
    }

    public AgentRun startRun(Long projectId, String runType, String modelName, String promptVersion) {
        AgentRun run = new AgentRun(
                null,
                projectId,
                runType,
                "running",
                modelName,
                promptVersion,
                null,
                null,
                null,
                null,
                Instant.now(),
                null
        );
        AgentRun saved = runRepository.save(run);
        recordEvent(saved.id(), "RUN_STARTED", runType, "Run started", "success", null, null);
        return saved;
    }

    public AgentRun finishRun(AgentRun run, int inputTokenEstimate, int outputTokenEstimate) {
        Instant finishedAt = Instant.now();
        long durationMs = Duration.between(run.createdAt(), finishedAt).toMillis();
        AgentRun finished = new AgentRun(
                run.id(),
                run.projectId(),
                run.runType(),
                "success",
                run.modelName(),
                run.promptVersion(),
                inputTokenEstimate,
                outputTokenEstimate,
                durationMs,
                null,
                run.createdAt(),
                finishedAt
        );
        AgentRun saved = runRepository.update(finished);
        recordEvent(saved.id(), "RUN_FINISHED", saved.runType(), "Run finished", "success", durationMs, null);
        return saved;
    }

    public AgentRun failRun(AgentRun run, String errorMessage) {
        Instant finishedAt = Instant.now();
        long durationMs = Duration.between(run.createdAt(), finishedAt).toMillis();
        AgentRun failed = new AgentRun(
                run.id(),
                run.projectId(),
                run.runType(),
                "failed",
                run.modelName(),
                run.promptVersion(),
                run.inputTokenEstimate(),
                run.outputTokenEstimate(),
                durationMs,
                errorMessage,
                run.createdAt(),
                finishedAt
        );
        AgentRun saved = runRepository.update(failed);
        recordEvent(saved.id(), "RUN_FINISHED", saved.runType(), "Run failed", "failed", durationMs, errorMessage);
        return saved;
    }

    public AgentEvent recordEvent(Long runId, String eventType, String inputSummary, String outputSummary, String status, Long durationMs, String errorMessage) {
        AgentEvent event = new AgentEvent(
                null,
                runId,
                eventType,
                inputSummary,
                outputSummary,
                status,
                durationMs,
                errorMessage,
                Instant.now()
        );
        return eventRepository.save(event);
    }

    public AgentRun getRun(Long runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new ApiException("AGENT_RUN_NOT_FOUND", "Agent run not found", HttpStatus.NOT_FOUND));
    }

    public List<AgentRun> listRuns(Long projectId, int limit) {
        return runRepository.findRecent(projectId, limit);
    }

    public List<AgentEvent> listEvents(Long runId) {
        getRun(runId);
        return eventRepository.findByRunId(runId);
    }
}
