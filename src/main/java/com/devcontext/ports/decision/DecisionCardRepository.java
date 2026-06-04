package com.devcontext.ports.decision;

import com.devcontext.domain.decision.DecisionCard;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DecisionCardRepository {

    DecisionCard save(DecisionCard decisionCard);

    Optional<DecisionCard> findById(Long decisionId);

    List<DecisionCard> findAll();

    List<DecisionCard> findRelevantToProject(Long projectId);

    List<DecisionCard> findByIds(Collection<Long> decisionIds);

    DecisionCard updateEmbeddingStatus(Long decisionId, String embeddingStatus, Instant embeddingUpdatedAt);

    DecisionCard updateStatus(Long decisionId, String status, Instant updatedAt);
}
