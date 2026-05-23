package com.devcontext.adapters.search;

import com.devcontext.domain.knowledge.KeywordSearchHit;
import com.devcontext.domain.knowledge.KnowledgeChunkView;
import com.devcontext.ports.knowledge.KeywordSearchEngine;
import com.devcontext.ports.knowledge.KnowledgeChunkRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SimpleKeywordSearchEngine implements KeywordSearchEngine {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "do", "does", "for", "from",
            "has", "have", "how", "in", "into", "is", "it", "of", "on", "or", "should",
            "that", "the", "this", "to", "what", "when", "where", "why", "with"
    );

    private final KnowledgeChunkRepository chunkRepository;

    public SimpleKeywordSearchEngine(KnowledgeChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    @Override
    public List<KeywordSearchHit> search(String query, Long sourceId, int topK) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        List<KnowledgeChunkView> chunks = sourceId == null
                ? chunkRepository.findAllViews()
                : chunkRepository.findViewsBySourceId(sourceId);
        Map<String, Integer> queryCounts = counts(queryTokens);
        return chunks.stream()
                .map(view -> new KeywordSearchHit(view.chunk().id(), score(view, queryCounts)))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(KeywordSearchHit::score).reversed())
                .limit(topK)
                .toList();
    }

    private double score(KnowledgeChunkView view, Map<String, Integer> queryCounts) {
        Map<String, Integer> contentCounts = counts(tokenize(view.chunk().content()));
        Map<String, Integer> headingCounts = counts(tokenize(view.chunk().headingPath()));
        Map<String, Integer> titleCounts = counts(tokenize(view.document().title() + " " + view.document().filePath()));
        double contentAndHeadingScore = 0;
        double titleScore = 0;
        for (Map.Entry<String, Integer> entry : queryCounts.entrySet()) {
            String token = entry.getKey();
            int queryWeight = entry.getValue();
            contentAndHeadingScore += queryWeight * Math.log1p(contentCounts.getOrDefault(token, 0));
            contentAndHeadingScore += queryWeight * 1.3 * headingCounts.getOrDefault(token, 0);
            titleScore += queryWeight * titleCounts.getOrDefault(token, 0);
        }
        double titleWeight = contentAndHeadingScore == 0 ? 0.25 : 0.8;
        return contentAndHeadingScore + titleScore * titleWeight;
    }

    private Map<String, Integer> counts(List<String> tokens) {
        Map<String, Integer> counts = new HashMap<>();
        for (String token : tokens) {
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            if (isHan(codePoint)) {
                flushToken(current, tokens);
                tokens.add(new String(Character.toChars(codePoint)));
                return;
            }
            if (Character.isLetterOrDigit(codePoint)) {
                current.appendCodePoint(Character.toLowerCase(codePoint));
                return;
            }
            flushToken(current, tokens);
        });
        flushToken(current, tokens);
        return tokens;
    }

    private void flushToken(StringBuilder current, List<String> tokens) {
        if (!current.isEmpty()) {
            String token = current.toString();
            if (token.length() > 1 && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
            current.setLength(0);
        }
    }

    private boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }
}
