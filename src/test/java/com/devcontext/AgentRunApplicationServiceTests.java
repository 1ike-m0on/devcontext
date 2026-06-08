package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.run.AgentEvent;
import com.devcontext.domain.run.AgentRun;
import com.devcontext.ports.run.AgentEventRepository;
import com.devcontext.ports.run.AgentRunRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentRunApplicationServiceTests {

    @Test
    void startRunRecordsConfiguredProviderAndModel() {
        assertRunProvenance(
                new DevContextLlmProperties("mock", new DevContextLlmProperties.Mock("mock-llm"), null, null),
                "mock",
                "mock-llm"
        );
        assertRunProvenance(
                new DevContextLlmProperties(
                        "gemini",
                        null,
                        new DevContextLlmProperties.Gemini("gemini-secret-for-test", "gemini-test-model", null, null),
                        null
                ),
                "gemini",
                "gemini-test-model"
        );
        assertRunProvenance(
                new DevContextLlmProperties(
                        "deepseek",
                        null,
                        null,
                        new DevContextLlmProperties.DeepSeek("deepseek-secret-for-test", "deepseek-test-model", null, null)
                ),
                "deepseek",
                "deepseek-test-model"
        );
    }

    private void assertRunProvenance(DevContextLlmProperties properties, String provider, String modelName) {
        InMemoryRunRepository runRepository = new InMemoryRunRepository();
        InMemoryEventRepository eventRepository = new InMemoryEventRepository();
        AgentRunApplicationService service = new AgentRunApplicationService(runRepository, eventRepository, properties);

        AgentRun run = service.startRun(7L, "LLM_TEST", "test-prompt");

        assertThat(run.provider()).isEqualTo(provider);
        assertThat(run.modelName()).isEqualTo(modelName);
        assertThat(eventRepository.events)
                .extracting(AgentEvent::eventType)
                .containsExactly("RUN_STARTED");
        assertThat(eventRepository.events.getFirst().inputSummary()).isEqualTo("LLM_TEST");
    }

    private static class InMemoryRunRepository implements AgentRunRepository {

        private final Map<Long, AgentRun> runs = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public AgentRun save(AgentRun run) {
            AgentRun saved = new AgentRun(
                    nextId++,
                    run.projectId(),
                    run.runType(),
                    run.status(),
                    run.provider(),
                    run.modelName(),
                    run.promptVersion(),
                    run.inputTokenEstimate(),
                    run.outputTokenEstimate(),
                    run.durationMs(),
                    run.errorMessage(),
                    run.createdAt(),
                    run.finishedAt()
            );
            runs.put(saved.id(), saved);
            return saved;
        }

        @Override
        public AgentRun update(AgentRun run) {
            runs.put(run.id(), run);
            return run;
        }

        @Override
        public Optional<AgentRun> findById(Long id) {
            return Optional.ofNullable(runs.get(id));
        }

        @Override
        public List<AgentRun> findRecent(Long projectId, int limit) {
            return runs.values().stream()
                    .filter(run -> projectId == null || projectId.equals(run.projectId()))
                    .limit(limit)
                    .toList();
        }
    }

    private static class InMemoryEventRepository implements AgentEventRepository {

        private final List<AgentEvent> events = new ArrayList<>();
        private long nextId = 1;

        @Override
        public AgentEvent save(AgentEvent event) {
            AgentEvent saved = new AgentEvent(
                    nextId++,
                    event.runId(),
                    event.eventType(),
                    event.inputSummary(),
                    event.outputSummary(),
                    event.status(),
                    event.durationMs(),
                    event.errorMessage(),
                    event.createdAt() == null ? Instant.now() : event.createdAt()
            );
            events.add(saved);
            return saved;
        }

        @Override
        public List<AgentEvent> findByRunId(Long runId) {
            return events.stream()
                    .filter(event -> runId.equals(event.runId()))
                    .toList();
        }
    }
}
