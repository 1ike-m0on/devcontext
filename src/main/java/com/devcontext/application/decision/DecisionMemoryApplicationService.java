package com.devcontext.application.decision;

import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.common.error.ApiException;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.decision.DecisionCard;
import com.devcontext.domain.decision.DecisionCreateResult;
import com.devcontext.domain.decision.DecisionReuseAdviceResult;
import com.devcontext.domain.decision.DecisionReuseRecord;
import com.devcontext.domain.decision.DecisionSearchResponse;
import com.devcontext.domain.decision.DecisionSearchResult;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.domain.run.AgentRun;
import com.devcontext.ports.decision.DecisionCardRepository;
import com.devcontext.ports.decision.DecisionReuseRecordRepository;
import com.devcontext.ports.llm.LlmClient;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DecisionMemoryApplicationService {

    private static final Set<String> VALID_STATUSES = Set.of("draft", "active", "deprecated");

    private final DecisionCardRepository decisionCardRepository;
    private final DecisionReuseRecordRepository reuseRecordRepository;
    private final DecisionSearchService searchService;
    private final DecisionPromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final DevContextLlmProperties llmProperties;
    private final AgentRunApplicationService runService;

    public DecisionMemoryApplicationService(
            DecisionCardRepository decisionCardRepository,
            DecisionReuseRecordRepository reuseRecordRepository,
            DecisionSearchService searchService,
            DecisionPromptBuilder promptBuilder,
            LlmClient llmClient,
            DevContextLlmProperties llmProperties,
            AgentRunApplicationService runService
    ) {
        this.decisionCardRepository = decisionCardRepository;
        this.reuseRecordRepository = reuseRecordRepository;
        this.searchService = searchService;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.runService = runService;
    }

    public DecisionCreateResult createDecision(CreateDecisionCommand command) {
        requireText(command.title(), "title");
        requireText(command.scenario(), "scenario");
        requireText(command.decision(), "decision");
        String status = normalizeStatus(command.status());
        Instant now = Instant.now();
        DecisionCard saved = decisionCardRepository.save(new DecisionCard(
                null,
                command.projectId(),
                command.title().trim(),
                command.scenario().trim(),
                safeList(command.options()),
                command.decision().trim(),
                safeList(command.reasons()),
                safeList(command.tradeOffs()),
                safeList(command.applicableWhen()),
                safeList(command.notApplicableWhen()),
                emptyToNull(command.outcome()),
                command.evidence() == null ? List.of() : command.evidence(),
                status,
                safeList(command.tags()),
                now,
                now
        ));
        return new DecisionCreateResult(saved.id(), saved.status());
    }

    public DecisionCard getDecision(Long decisionId) {
        return decisionCardRepository.findById(decisionId)
                .orElseThrow(() -> new ApiException("DECISION_NOT_FOUND", "Decision card not found", HttpStatus.NOT_FOUND));
    }

    public DecisionSearchResponse search(DecisionSearchCommand command) {
        return searchService.search(command);
    }

    public DecisionReuseAdviceResult reuseAdvice(DecisionReuseAdviceCommand command) {
        requireText(command.query(), "query");
        AgentRun run = runService.startRun(command.projectId(), "DECISION_REUSE_ADVICE", llmProperties.modelName(), "mvp3");
        try {
            DecisionSearchResponse searchResponse = searchService.search(new DecisionSearchCommand(
                    command.query(),
                    command.projectId(),
                    command.tags(),
                    command.topK()
            ));
            List<DecisionSearchResult> matches = searchResponse.matches();
            runService.recordEvent(run.id(), "DECISION_MEMORY_RECALLED", command.query(), matches.size() + " decision cards recalled", "success", null, null);

            String prompt = promptBuilder.build(command, matches);
            runService.recordEvent(run.id(), "PROMPT_BUILT", "decision reuse prompt", prompt.length() + " chars", "success", null, null);

            LlmResponse response = llmClient.chat(new LlmRequest(prompt, llmProperties.modelName()));
            runService.recordEvent(run.id(), "LLM_CALLED", llmProperties.modelName(), "LLM response generated", "success", null, null);

            List<Long> matchedIds = matches.stream()
                    .map(match -> match.decision().id())
                    .toList();
            DecisionReuseRecord record = reuseRecordRepository.save(new DecisionReuseRecord(
                    null,
                    command.query().trim(),
                    command.projectId(),
                    matchedIds,
                    response.content(),
                    null,
                    Instant.now()
            ));
            runService.recordEvent(run.id(), "REUSE_ADVICE_SAVED", matchedIds.toString(), "Decision reuse record " + record.id() + " saved", "success", null, null);
            runService.finishRun(run, response.inputTokenEstimate(), response.outputTokenEstimate());
            return new DecisionReuseAdviceResult(run.id(), record.id(), command.query().trim(), matches, response.content());
        } catch (RuntimeException e) {
            runService.failRun(run, e.getMessage());
            throw e;
        }
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ApiException("DECISION_FIELD_REQUIRED", fieldName + " is required", HttpStatus.BAD_REQUEST);
        }
    }

    private String normalizeStatus(String status) {
        String normalized = status == null || status.isBlank()
                ? "draft"
                : status.trim().toLowerCase(Locale.ROOT);
        if (!VALID_STATUSES.contains(normalized)) {
            throw new ApiException("DECISION_STATUS_INVALID", "Invalid decision status", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private List<String> safeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
