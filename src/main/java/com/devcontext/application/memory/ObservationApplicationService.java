package com.devcontext.application.memory;

import com.devcontext.common.error.ApiException;
import com.devcontext.domain.memory.Observation;
import com.devcontext.ports.memory.ObservationRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ObservationApplicationService {

    private final ObservationRepository observationRepository;

    public ObservationApplicationService(ObservationRepository observationRepository) {
        this.observationRepository = observationRepository;
    }

    public Observation getObservation(Long observationId) {
        return observationRepository.findById(observationId)
                .orElseThrow(() -> new ApiException("OBSERVATION_NOT_FOUND", "Observation not found", HttpStatus.NOT_FOUND));
    }

    public List<Observation> listObservations(Long projectId, String taskType, String lifecycle, Long runId, Long reviewId, Long retrievalId, int limit) {
        return observationRepository.findRecent(projectId, taskType, lifecycle, runId, reviewId, retrievalId, normalizeLimit(limit));
    }

    public List<Observation> listRunObservations(Long runId) {
        return observationRepository.findByRunId(runId);
    }

    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }
}
