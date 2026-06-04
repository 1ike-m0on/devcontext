package com.devcontext.application.decision;

import com.devcontext.domain.decision.DecisionCard;
import com.devcontext.domain.decision.DecisionSearchResponse;
import com.devcontext.domain.decision.DecisionSearchResult;
import com.devcontext.domain.knowledge.EmbeddingVector;
import com.devcontext.domain.knowledge.VectorQuery;
import com.devcontext.domain.knowledge.VectorSearchHit;
import com.devcontext.ports.decision.DecisionCardRepository;
import com.devcontext.ports.knowledge.EmbeddingClient;
import com.devcontext.ports.knowledge.VectorStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class DecisionSearchService {

    private static final Pattern ASCII_TOKEN_PATTERN = Pattern.compile("[a-z0-9_]{2,}");
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    private final DecisionCardRepository decisionCardRepository;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    public DecisionSearchService(
            DecisionCardRepository decisionCardRepository,
            EmbeddingClient embeddingClient,
            VectorStore vectorStore
    ) {
        this.decisionCardRepository = decisionCardRepository;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    public DecisionSearchResponse search(DecisionSearchCommand command) {
        String query = command.query() == null ? "" : command.query().trim();
        List<String> requestedTags = normalizeList(command.tags());
        Set<String> queryTokens = tokenize(query);
        Set<String> requestedTagSet = new LinkedHashSet<>(requestedTags);
        int limit = normalizeTopK(command.topK());

        List<DecisionCard> candidates = command.projectId() == null
                ? decisionCardRepository.findAll()
                : decisionCardRepository.findRelevantToProject(command.projectId());
        List<DecisionCard> activeCandidates = candidates.stream()
                .filter(card -> "active".equalsIgnoreCase(card.status()))
                .filter(card -> matchesRequestedTags(card, requestedTagSet))
                .toList();
        Map<Long, Double> vectorScores = vectorScores(query, activeCandidates, command.projectId(), limit);

        List<DecisionSearchResult> matches = activeCandidates.stream()
                .map(card -> score(card, queryTokens, requestedTagSet, vectorScores.getOrDefault(card.id(), 0.0)))
                .filter(result -> result.score() > 0)
                .sorted(Comparator.comparingDouble(DecisionSearchResult::score).reversed()
                        .thenComparing(result -> result.decision().updatedAt(), Comparator.reverseOrder())
                        .thenComparing(result -> result.decision().id(), Comparator.reverseOrder()))
                .limit(limit)
                .toList();

        return new DecisionSearchResponse(query, matches);
    }

    private DecisionSearchResult score(
            DecisionCard card,
            Set<String> queryTokens,
            Set<String> requestedTags,
            double vectorScore
    ) {
        Set<String> cardTags = new LinkedHashSet<>(normalizeList(card.tags()));
        List<String> matchedTags = requestedTags.stream()
                .filter(cardTags::contains)
                .toList();

        Set<String> cardTokens = searchableTokens(card);
        List<String> matchedTerms = queryTokens.stream()
                .filter(cardTokens::contains)
                .toList();

        double tagScore = matchedTags.size() * 1.5;
        double tokenScore = queryTokens.isEmpty() ? 0 : (double) matchedTerms.size() / queryTokens.size();
        double titleBoost = hasTitleMatch(card, queryTokens) ? 0.25 : 0;
        double keywordScore = tokenScore + titleBoost;
        double weightedVectorScore = vectorScore * 1.2;
        double score = tagScore + keywordScore + weightedVectorScore;

        List<String> matchReasons = new ArrayList<>();
        if (!matchedTags.isEmpty()) {
            matchReasons.add("tag");
        }
        if (keywordScore > 0) {
            matchReasons.add("keyword");
        }
        if (vectorScore > 0) {
            matchReasons.add("vector");
        }

        return new DecisionSearchResult(
                card,
                round(score),
                matchedTags,
                matchedTerms,
                round(tagScore),
                round(keywordScore),
                round(vectorScore),
                matchReasons
        );
    }

    private Map<Long, Double> vectorScores(String query, List<DecisionCard> candidates, Long projectId, int limit) {
        Map<Long, Double> scores = new HashMap<>();
        if (query.isBlank() || candidates.isEmpty()) {
            return scores;
        }
        Set<Long> candidateIds = new LinkedHashSet<>(candidates.stream().map(DecisionCard::id).toList());
        EmbeddingVector embedding = embeddingClient.embed(query);
        List<VectorSearchHit> hits = vectorStore.search(new VectorQuery(
                DecisionVectorService.COLLECTION,
                null,
                embedding,
                Math.min(MAX_TOP_K, Math.max(limit * 4, DEFAULT_TOP_K)),
                vectorFilters(projectId)
        ));
        for (VectorSearchHit hit : hits) {
            Long decisionId = DecisionVectorService.decisionIdFromVectorId(hit.vectorId());
            if (decisionId != null && candidateIds.contains(decisionId)) {
                scores.put(decisionId, Math.max(scores.getOrDefault(decisionId, 0.0), hit.score()));
            }
        }
        return scores;
    }

    private Map<String, Object> vectorFilters(Long projectId) {
        Map<String, Object> filters = new HashMap<>();
        filters.put("status", "active");
        if (projectId != null) {
            filters.put("projectScopes", List.of("global", String.valueOf(projectId)));
        }
        return filters;
    }

    private Set<String> searchableTokens(DecisionCard card) {
        String searchableText = String.join(" ",
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
        Set<String> tokens = tokenize(searchableText);
        tokens.addAll(normalizeList(card.tags()));
        return tokens;
    }

    private boolean hasTitleMatch(DecisionCard card, Set<String> queryTokens) {
        Set<String> titleTokens = tokenize(card.title());
        return queryTokens.stream().anyMatch(titleTokens::contains);
    }

    private boolean matchesRequestedTags(DecisionCard card, Set<String> requestedTags) {
        if (requestedTags.isEmpty()) {
            return true;
        }
        Set<String> cardTags = new LinkedHashSet<>(normalizeList(card.tags()));
        return cardTags.containsAll(requestedTags);
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

    private List<String> normalizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
