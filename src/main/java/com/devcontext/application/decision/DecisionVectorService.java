package com.devcontext.application.decision;

import com.devcontext.domain.decision.DecisionCard;
import com.devcontext.domain.decision.DecisionEvidence;
import com.devcontext.domain.knowledge.EmbeddingVector;
import com.devcontext.domain.knowledge.VectorDocument;
import com.devcontext.ports.decision.DecisionCardRepository;
import com.devcontext.ports.knowledge.EmbeddingClient;
import com.devcontext.ports.knowledge.VectorStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DecisionVectorService {

    public static final String COLLECTION = "decision_card";
    private static final String VECTOR_ID_PREFIX = "decision:";

    private final DecisionCardRepository decisionCardRepository;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    public DecisionVectorService(
            DecisionCardRepository decisionCardRepository,
            EmbeddingClient embeddingClient,
            VectorStore vectorStore
    ) {
        this.decisionCardRepository = decisionCardRepository;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    public DecisionCard index(DecisionCard card) {
        try {
            EmbeddingVector embedding = embeddingClient.embed(toEmbeddingText(card));
            vectorStore.upsert(new VectorDocument(
                    vectorId(card.id()),
                    COLLECTION,
                    card.projectId(),
                    embedding,
                    metadata(card)
            ));
            return decisionCardRepository.updateEmbeddingStatus(card.id(), "indexed", Instant.now());
        } catch (RuntimeException e) {
            decisionCardRepository.updateEmbeddingStatus(card.id(), "failed", Instant.now());
            throw e;
        }
    }

    public DecisionCard rebuild(Long decisionId) {
        DecisionCard card = decisionCardRepository.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("Decision card not found"));
        return index(card);
    }

    public static String vectorId(Long decisionId) {
        return VECTOR_ID_PREFIX + decisionId;
    }

    public static Long decisionIdFromVectorId(String vectorId) {
        if (vectorId == null || !vectorId.startsWith(VECTOR_ID_PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(vectorId.substring(VECTOR_ID_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> metadata(DecisionCard card) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("decisionId", String.valueOf(card.id()));
        metadata.put("projectScope", projectScope(card.projectId()));
        if (card.projectId() != null) {
            metadata.put("projectId", card.projectId());
        }
        metadata.put("title", valueOrEmpty(card.title()));
        metadata.put("status", valueOrEmpty(card.status()));
        metadata.put("tags", safeList(card.tags()));
        metadata.put("decisionType", "architecture");
        metadata.put("createdAt", card.createdAt().toString());
        metadata.put("updatedAt", card.updatedAt().toString());
        metadata.put("applicableWhen", safeList(card.applicableWhen()));
        metadata.put("notApplicableWhen", safeList(card.notApplicableWhen()));
        return metadata;
    }

    private String projectScope(Long projectId) {
        return projectId == null ? "global" : String.valueOf(projectId);
    }

    private String toEmbeddingText(DecisionCard card) {
        StringBuilder text = new StringBuilder();
        append(text, "title", card.title());
        append(text, "scenario", card.scenario());
        append(text, "options", String.join("; ", safeList(card.options())));
        append(text, "decision", card.decision());
        append(text, "reasons", String.join("; ", safeList(card.reasons())));
        append(text, "trade offs", String.join("; ", safeList(card.tradeOffs())));
        append(text, "applicable when", String.join("; ", safeList(card.applicableWhen())));
        append(text, "not applicable when", String.join("; ", safeList(card.notApplicableWhen())));
        append(text, "outcome", card.outcome());
        append(text, "tags", String.join("; ", safeList(card.tags())));
        if (card.evidence() != null) {
            for (DecisionEvidence evidence : card.evidence()) {
                append(text, "evidence", String.join(" ",
                        valueOrEmpty(evidence.type()),
                        valueOrEmpty(evidence.ref()),
                        valueOrEmpty(evidence.summary())
                ));
            }
        }
        return text.toString();
    }

    private void append(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(label).append(": ").append(value.trim()).append("\n");
        }
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
