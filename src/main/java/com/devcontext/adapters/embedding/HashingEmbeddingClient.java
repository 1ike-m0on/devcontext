package com.devcontext.adapters.embedding;

import com.devcontext.domain.knowledge.EmbeddingVector;
import com.devcontext.ports.knowledge.EmbeddingClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class HashingEmbeddingClient implements EmbeddingClient {

    private static final int DIMENSIONS = 64;

    @Override
    public EmbeddingVector embed(String text) {
        double[] values = new double[DIMENSIONS];
        for (String token : tokenize(text)) {
            int hash = token.hashCode();
            int index = Math.floorMod(hash, DIMENSIONS);
            values[index] += 1.0;
        }
        return new EmbeddingVector(normalize(values));
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
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private List<Double> normalize(double[] values) {
        double norm = 0;
        for (double value : values) {
            norm += value * value;
        }
        if (norm == 0) {
            return Collections.nCopies(DIMENSIONS, 0.0);
        }
        double sqrt = Math.sqrt(norm);
        List<Double> normalized = new ArrayList<>(DIMENSIONS);
        for (double value : values) {
            normalized.add(value / sqrt);
        }
        return normalized;
    }
}
