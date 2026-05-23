package com.devcontext.ports.knowledge;

import com.devcontext.domain.knowledge.RetrievalRecord;
import java.util.List;
import java.util.Optional;

public interface RetrievalRecordRepository {

    RetrievalRecord save(RetrievalRecord record);

    Optional<RetrievalRecord> findById(Long recordId);

    List<RetrievalRecord> findByRunId(Long runId);
}
