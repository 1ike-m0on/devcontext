package com.devcontext.ports.decision;

import com.devcontext.domain.decision.DecisionCard;
import java.util.List;
import java.util.Optional;

public interface DecisionCardRepository {

    DecisionCard save(DecisionCard decisionCard);

    Optional<DecisionCard> findById(Long decisionId);

    List<DecisionCard> findAll();

    List<DecisionCard> findRelevantToProject(Long projectId);
}
