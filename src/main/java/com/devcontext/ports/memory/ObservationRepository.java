package com.devcontext.ports.memory;

import com.devcontext.domain.memory.Observation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ObservationRepository {

    Observation save(Observation observation);

    Observation upsertBySourceKey(Observation observation);

    Optional<Observation> findById(Long id);

    Optional<Observation> updateLifecycle(Long id, String lifecycle, Instant updatedAt);

    List<Observation> findRecent(Long projectId, String taskType, String lifecycle, int limit);

    List<Observation> findRecent(Long projectId, String taskType, String lifecycle, Long runId, Long reviewId, Long retrievalId, int limit);

    List<Observation> findByRunId(Long runId);

    List<Observation> findByReviewId(Long reviewId);

    List<Observation> findByRetrievalId(Long retrievalId);
}
