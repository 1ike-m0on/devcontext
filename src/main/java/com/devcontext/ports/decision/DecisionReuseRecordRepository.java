package com.devcontext.ports.decision;

import com.devcontext.domain.decision.DecisionReuseRecord;
import java.util.Optional;

public interface DecisionReuseRecordRepository {

    DecisionReuseRecord save(DecisionReuseRecord record);

    Optional<DecisionReuseRecord> findById(Long recordId);

    DecisionReuseRecord updateFeedback(Long recordId, String status, Boolean accepted, String userFeedback);
}
