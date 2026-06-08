package com.devcontext.application.decision;

import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.common.error.ApiException;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.decision.DecisionBatchStatusUpdateResult;
import com.devcontext.domain.decision.DecisionCard;
import com.devcontext.domain.decision.DecisionCreateResult;
import com.devcontext.domain.decision.DecisionDuplicateCandidatesResponse;
import com.devcontext.domain.decision.DecisionDuplicatePair;
import com.devcontext.domain.decision.DecisionEmbeddingRebuildResult;
import com.devcontext.domain.decision.DecisionReuseAdviceResult;
import com.devcontext.domain.decision.DecisionReuseRecord;
import com.devcontext.domain.decision.DecisionSearchResponse;
import com.devcontext.domain.decision.DecisionSearchResult;
import com.devcontext.domain.decision.DecisionStatusUpdateResult;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.domain.run.AgentRun;
import com.devcontext.ports.decision.DecisionCardRepository;
import com.devcontext.ports.decision.DecisionReuseRecordRepository;
import com.devcontext.ports.llm.LlmClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DecisionMemoryApplicationService {

    private static final Set<String> VALID_STATUSES = Set.of("draft", "active", "deprecated");
    private static final Set<String> VALID_REUSE_STATUSES = Set.of(
            "pending",
            "accepted",
            "rejected",
            "partially_reused",
            "false_positive",
            "superseded"
    );
    private static final Pattern ASCII_TOKEN_PATTERN = Pattern.compile("[a-z0-9_]{2,}");
    private static final double DEFAULT_DUPLICATE_MIN_SCORE = 0.75;

    private final DecisionCardRepository decisionCardRepository;
    private final DecisionReuseRecordRepository reuseRecordRepository;
    private final DecisionSearchService searchService;
    private final DecisionVectorService decisionVectorService;
    private final DecisionPromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final DevContextLlmProperties llmProperties;
    private final AgentRunApplicationService runService;

    public DecisionMemoryApplicationService(
            DecisionCardRepository decisionCardRepository,
            DecisionReuseRecordRepository reuseRecordRepository,
            DecisionSearchService searchService,
            DecisionVectorService decisionVectorService,
            DecisionPromptBuilder promptBuilder,
            LlmClient llmClient,
            DevContextLlmProperties llmProperties,
            AgentRunApplicationService runService
    ) {
        this.decisionCardRepository = decisionCardRepository;
        this.reuseRecordRepository = reuseRecordRepository;
        this.searchService = searchService;
        this.decisionVectorService = decisionVectorService;
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
                "pending",
                null,
                now,
                now
        ));
        DecisionCard indexed = decisionVectorService.index(saved);
        return new DecisionCreateResult(indexed.id(), indexed.status());
    }

    public DecisionCard getDecision(Long decisionId) {
        return decisionCardRepository.findById(decisionId)
                .orElseThrow(() -> new ApiException("DECISION_NOT_FOUND", "Decision card not found", HttpStatus.NOT_FOUND));
    }

    public DecisionSearchResponse search(DecisionSearchCommand command) {
        return searchService.search(command);
    }

    public List<DecisionCard> listDecisions(DecisionListCommand command) {
        String status = normalizeOptionalStatus(command.status());
        String tag = normalizeOptionalText(command.tag());
        String query = normalizeOptionalText(command.query());
        List<DecisionCard> candidates = command.projectId() == null
                ? decisionCardRepository.findAll()
                : decisionCardRepository.findRelevantToProject(command.projectId());
        return candidates.stream()
                .filter(card -> status == null || status.equalsIgnoreCase(card.status()))
                .filter(card -> tag == null || hasTag(card, tag))
                .filter(card -> query == null || containsQuery(card, query))
                .sorted(Comparator.comparing(DecisionCard::updatedAt).reversed()
                        .thenComparing(DecisionCard::id, Comparator.reverseOrder()))
                .toList();
    }

    public DecisionCard rebuildEmbedding(Long decisionId) {
        getDecision(decisionId);
        return decisionVectorService.rebuild(decisionId);
    }

    public DecisionStatusUpdateResult updateDecisionStatus(UpdateDecisionStatusCommand command) {
        if (command.decisionId() == null) {
            throw new ApiException("DECISION_ID_REQUIRED", "decisionId is required", HttpStatus.BAD_REQUEST);
        }
        DecisionCard current = getDecision(command.decisionId());
        String status = normalizeStatus(command.status());
        AgentRun run = runService.startRun(current.projectId(), "DECISION_STATUS_UPDATE", "v0.5.1");
        try {
            DecisionCard updated = decisionCardRepository.updateStatus(current.id(), status, Instant.now());
            runService.recordEvent(run.id(), "DECISION_STATUS_CHANGED", current.status(), status, "success", null, null);

            DecisionCard indexed = decisionVectorService.index(updated);
            runService.recordEvent(run.id(), "DECISION_INDEXED", String.valueOf(indexed.id()), indexed.embeddingStatus(), "success", null, null);

            runService.finishRun(run, 0, 0);
            return new DecisionStatusUpdateResult(run.id(), indexed);
        } catch (RuntimeException e) {
            runService.failRun(run, e.getMessage());
            throw e;
        }
    }

    public DecisionBatchStatusUpdateResult batchUpdateDecisionStatus(BatchUpdateDecisionStatusCommand command) {
        if (command.decisionIds() == null || command.decisionIds().isEmpty()) {
            throw new ApiException("DECISION_IDS_REQUIRED", "decisionIds is required", HttpStatus.BAD_REQUEST);
        }
        String status = normalizeStatus(command.status());
        AgentRun run = runService.startRun(null, "DECISION_BATCH_STATUS_UPDATE", "v0.5.2");
        try {
            List<DecisionCard> updatedCards = new ArrayList<>();
            for (Long decisionId : distinctIds(command.decisionIds())) {
                DecisionCard current = getDecision(decisionId);
                DecisionCard updated = decisionCardRepository.updateStatus(current.id(), status, Instant.now());
                runService.recordEvent(run.id(), "DECISION_BATCH_STATUS_CHANGED", current.id() + ":" + current.status(), status, "success", null, null);
                DecisionCard indexed = decisionVectorService.index(updated);
                runService.recordEvent(run.id(), "DECISION_INDEXED", String.valueOf(indexed.id()), indexed.embeddingStatus(), "success", null, null);
                updatedCards.add(indexed);
            }
            runService.finishRun(run, 0, 0);
            return new DecisionBatchStatusUpdateResult(run.id(), updatedCards.size(), updatedCards);
        } catch (RuntimeException e) {
            runService.failRun(run, e.getMessage());
            throw e;
        }
    }

    public DecisionEmbeddingRebuildResult rebuildDecisionEmbeddings(RebuildDecisionEmbeddingsCommand command) {
        List<DecisionCard> targets = listDecisions(new DecisionListCommand(
                command.status(),
                command.projectId(),
                command.tag(),
                command.query()
        ));
        AgentRun run = runService.startRun(command.projectId(), "DECISION_BATCH_REINDEX", "v0.5.2");
        try {
            runService.recordEvent(run.id(), "DECISION_BATCH_REINDEX_STARTED", "requested", targets.size() + " decision cards", "success", null, null);
            List<DecisionCard> indexedCards = new ArrayList<>();
            List<Long> failedIds = new ArrayList<>();
            for (DecisionCard target : targets) {
                try {
                    DecisionCard indexed = decisionVectorService.index(target);
                    indexedCards.add(indexed);
                    runService.recordEvent(run.id(), "DECISION_INDEXED", String.valueOf(indexed.id()), indexed.embeddingStatus(), "success", null, null);
                } catch (RuntimeException e) {
                    failedIds.add(target.id());
                    runService.recordEvent(run.id(), "DECISION_INDEX_FAILED", String.valueOf(target.id()), e.getMessage(), "failed", null, e.getMessage());
                }
            }
            runService.finishRun(run, 0, 0);
            return new DecisionEmbeddingRebuildResult(run.id(), targets.size(), indexedCards.size(), failedIds.size(), failedIds, indexedCards);
        } catch (RuntimeException e) {
            runService.failRun(run, e.getMessage());
            throw e;
        }
    }

    public DecisionDuplicateCandidatesResponse duplicateCandidates(DecisionDuplicateCandidatesCommand command) {
        double minScore = normalizeDuplicateMinScore(command.minScore());
        String status = command.status() == null || command.status().isBlank() ? "active" : command.status();
        List<DecisionCard> cards = listDecisions(new DecisionListCommand(
                status,
                command.projectId(),
                command.tag(),
                command.query()
        ));
        List<DecisionDuplicatePair> pairs = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            for (int j = i + 1; j < cards.size(); j++) {
                DuplicateScore duplicateScore = scoreDuplicate(cards.get(i), cards.get(j));
                if (duplicateScore.score() >= minScore) {
                    pairs.add(new DecisionDuplicatePair(
                            cards.get(i),
                            cards.get(j),
                            duplicateScore.score(),
                            duplicateScore.reasons()
                    ));
                }
            }
        }
        pairs.sort(Comparator.comparingDouble(DecisionDuplicatePair::score).reversed()
                .thenComparing(pair -> pair.left().id())
                .thenComparing(pair -> pair.right().id()));
        return new DecisionDuplicateCandidatesResponse(minScore, pairs);
    }

    public DecisionReuseAdviceResult reuseAdvice(DecisionReuseAdviceCommand command) {
        requireText(command.query(), "query");
        AgentRun run = runService.startRun(command.projectId(), "DECISION_REUSE_ADVICE", "v0.5");
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
            runService.recordEvent(run.id(), "LLM_CALLED", llmProperties.providerModelLabel(), "LLM response generated", "success", null, null);

            List<Long> matchedIds = matches.stream()
                    .map(match -> match.decision().id())
                    .toList();
            DecisionReuseRecord record = reuseRecordRepository.save(new DecisionReuseRecord(
                    null,
                    run.id(),
                    command.query().trim(),
                    command.projectId(),
                    matchedIds,
                    response.content(),
                    "pending",
                    null,
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

    public DecisionReuseRecord updateReuseFeedback(UpdateDecisionReuseFeedbackCommand command) {
        if (command.recordId() == null) {
            throw new ApiException("DECISION_REUSE_RECORD_ID_REQUIRED", "recordId is required", HttpStatus.BAD_REQUEST);
        }
        reuseRecordRepository.findById(command.recordId())
                .orElseThrow(() -> new ApiException("DECISION_REUSE_RECORD_NOT_FOUND", "Decision reuse record not found", HttpStatus.NOT_FOUND));
        String feedback = command.userFeedback() == null || command.userFeedback().isBlank()
                ? null
                : command.userFeedback().trim();
        String status = normalizeReuseStatus(command.status(), command.accepted());
        Boolean accepted = acceptedFromReuseStatus(status);
        DecisionReuseRecord updated = reuseRecordRepository.updateFeedback(command.recordId(), status, accepted, feedback);
        if (updated.runId() != null) {
            runService.recordEvent(
                    updated.runId(),
                    "DECISION_REUSE_FEEDBACK_SAVED",
                    status,
                    feedback == null ? "No feedback note" : feedback,
                    "success",
                    null,
                    null
            );
        }
        return updated;
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

    private String normalizeOptionalStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return normalizeStatus(status);
    }

    private String normalizeReuseStatus(String status, Boolean accepted) {
        if (status == null || status.isBlank()) {
            if (accepted == null) {
                return "pending";
            }
            return accepted ? "accepted" : "rejected";
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if (!VALID_REUSE_STATUSES.contains(normalized)) {
            throw new ApiException("DECISION_REUSE_STATUS_INVALID", "Invalid decision reuse status", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private Boolean acceptedFromReuseStatus(String status) {
        return switch (status) {
            case "accepted", "partially_reused" -> true;
            case "rejected", "false_positive", "superseded" -> false;
            default -> null;
        };
    }

    private List<Long> distinctIds(List<Long> decisionIds) {
        return decisionIds.stream()
                .filter(id -> id != null && id > 0)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private boolean hasTag(DecisionCard card, String tag) {
        return safeList(card.tags()).stream()
                .anyMatch(value -> value.equalsIgnoreCase(tag));
    }

    private boolean containsQuery(DecisionCard card, String query) {
        return searchableText(card).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private DuplicateScore scoreDuplicate(DecisionCard left, DecisionCard right) {
        Set<String> leftTokens = tokenize(searchableText(left));
        Set<String> rightTokens = tokenize(searchableText(right));
        double textScore = jaccard(leftTokens, rightTokens);
        double titleScore = normalizedEquals(left.title(), right.title()) ? 1.0 : jaccard(tokenize(left.title()), tokenize(right.title()));
        double decisionScore = normalizedEquals(left.decision(), right.decision()) ? 1.0 : jaccard(tokenize(left.decision()), tokenize(right.decision()));
        double tagScore = jaccard(new LinkedHashSet<>(normalizeList(left.tags())), new LinkedHashSet<>(normalizeList(right.tags())));
        double score = round(textScore * 0.45 + titleScore * 0.2 + decisionScore * 0.25 + tagScore * 0.1);

        List<String> reasons = new ArrayList<>();
        if (titleScore >= 0.8) {
            reasons.add("title");
        }
        if (decisionScore >= 0.8) {
            reasons.add("decision");
        }
        if (tagScore > 0) {
            reasons.add("tags");
        }
        if (textScore >= 0.75) {
            reasons.add("content");
        }
        return new DuplicateScore(score, reasons);
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        Matcher matcher = ASCII_TOKEN_PATTERN.matcher(normalized);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        normalized.codePoints()
                .filter(this::isHanCharacter)
                .forEach(codePoint -> tokens.add(new String(Character.toChars(codePoint))));
        return tokens;
    }

    private boolean isHanCharacter(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private boolean normalizedEquals(String left, String right) {
        return normalizeOptionalText(left) != null && normalizeOptionalText(left).equals(normalizeOptionalText(right));
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .toList();
    }

    private String searchableText(DecisionCard card) {
        return String.join(" ",
                valueOrEmpty(card.title()),
                valueOrEmpty(card.scenario()),
                valueOrEmpty(card.decision()),
                valueOrEmpty(card.outcome()),
                String.join(" ", safeList(card.options())),
                String.join(" ", safeList(card.reasons())),
                String.join(" ", safeList(card.tradeOffs())),
                String.join(" ", safeList(card.applicableWhen())),
                String.join(" ", safeList(card.notApplicableWhen())),
                String.join(" ", safeList(card.tags()))
        );
    }

    private double normalizeDuplicateMinScore(Double minScore) {
        if (minScore == null) {
            return DEFAULT_DUPLICATE_MIN_SCORE;
        }
        if (minScore < 0 || minScore > 1) {
            throw new ApiException("DECISION_DUPLICATE_SCORE_INVALID", "minScore must be between 0 and 1", HttpStatus.BAD_REQUEST);
        }
        return minScore;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
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

    private String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private record DuplicateScore(double score, List<String> reasons) {
    }
}
