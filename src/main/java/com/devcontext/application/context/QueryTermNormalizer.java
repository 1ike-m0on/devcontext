package com.devcontext.application.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class QueryTermNormalizer {

    private static final String DICTIONARY_RESOURCE = "context-query-dictionary.json";
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "how", "what", "where", "when", "why",
            "this", "that", "should", "could", "would", "does", "about", "from",
            "项目", "这个", "怎么", "如何", "哪里", "什么", "一下", "是否"
    );

    private final List<QueryConcept> concepts;

    public QueryTermNormalizer(ObjectMapper objectMapper) {
        this.concepts = loadDictionary(objectMapper).concepts();
    }

    public List<String> normalize(String question) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String safeQuestion = safe(question);
        tokenize(safeQuestion).stream()
                .filter(term -> !STOP_WORDS.contains(term))
                .forEach(terms::add);

        String normalizedQuestion = normalizeLatinText(safeQuestion);
        String lowerQuestion = safeQuestion.toLowerCase(Locale.ROOT);
        for (QueryConcept concept : concepts) {
            if (matchesConcept(lowerQuestion, normalizedQuestion, concept)) {
                terms.addAll(nullToEmpty(concept.terms()));
            }
        }

        addCompoundTerms(terms);
        return terms.stream()
                .filter(term -> term != null && !term.isBlank())
                .limit(60)
                .toList();
    }

    private QueryDictionary loadDictionary(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(DICTIONARY_RESOURCE);
        try (InputStream inputStream = resource.getInputStream()) {
            QueryDictionary dictionary = objectMapper.readValue(inputStream, QueryDictionary.class);
            if (dictionary.concepts() == null || dictionary.concepts().isEmpty()) {
                throw new IllegalStateException("Context query dictionary has no concepts");
            }
            return dictionary;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load context query dictionary: " + DICTIONARY_RESOURCE, e);
        }
    }

    private boolean matchesConcept(String lowerQuestion, String normalizedQuestion, QueryConcept concept) {
        for (String alias : nullToEmpty(concept.aliases())) {
            if (matchesAlias(lowerQuestion, normalizedQuestion, alias)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAlias(String lowerQuestion, String normalizedQuestion, String alias) {
        if (alias == null || alias.isBlank()) {
            return false;
        }
        String lowerAlias = alias.toLowerCase(Locale.ROOT);
        if (lowerQuestion.contains(lowerAlias)) {
            return true;
        }
        String normalizedAlias = normalizeLatinText(alias);
        return !normalizedAlias.isBlank() && normalizedQuestion.contains(normalizedAlias);
    }

    private List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String separated = value
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([a-z\\d])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .toLowerCase(Locale.ROOT);
        return Arrays.stream(separated.split("\\s+"))
                .filter(token -> !token.isBlank())
                .filter(token -> token.length() >= 2)
                .distinct()
                .toList();
    }

    private String normalizeLatinText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([a-z\\d])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private void addCompoundTerms(Set<String> terms) {
        addIfAllPresent(terms, "flash-sale", "flash", "sale");
        addIfAllPresent(terms, "voucher-order", "voucher", "order");
        addIfAllPresent(terms, "rate-limit", "rate", "limit");
        addIfAllPresent(terms, "redis-lua", "redis", "lua");
        addIfAllPresent(terms, "stock-release", "stock", "release");
        addIfAllPresent(terms, "order-close", "order", "close");
        addIfAllPresent(terms, "auth-token", "auth", "token");
        if (terms.contains("rocketmq") || (terms.contains("rocket") && terms.contains("mq"))) {
            terms.add("rocketmq");
        }
    }

    private void addIfAllPresent(Set<String> terms, String compound, String first, String second) {
        if (terms.contains(first) && terms.contains(second)) {
            terms.add(compound);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record QueryDictionary(List<QueryConcept> concepts) {
    }

    private record QueryConcept(String id, List<String> terms, List<String> aliases) {
    }
}
