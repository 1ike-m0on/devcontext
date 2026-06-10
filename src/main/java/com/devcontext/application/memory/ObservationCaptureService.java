package com.devcontext.application.memory;

import com.devcontext.domain.decision.DecisionReuseRecord;
import com.devcontext.domain.knowledge.RetrievalRecord;
import com.devcontext.domain.memory.Observation;
import com.devcontext.domain.review.ReviewIssue;
import com.devcontext.domain.review.ReviewRecord;
import com.devcontext.domain.run.AgentEvent;
import com.devcontext.domain.run.AgentRun;
import com.devcontext.ports.memory.ObservationRepository;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ObservationCaptureService {

    private static final Logger log = LoggerFactory.getLogger(ObservationCaptureService.class);

    private final ObservationRepository observationRepository;
    private final ObservationMapper mapper;

    public ObservationCaptureService(ObservationRepository observationRepository, ObservationMapper mapper) {
        this.observationRepository = observationRepository;
        this.mapper = mapper;
    }

    public void captureAgentRun(AgentRun run) {
        capture("agent run " + id(run == null ? null : run.id()), () -> mapper.fromAgentRun(run));
    }

    public void captureAgentEvent(AgentEvent event) {
        capture("agent event " + id(event == null ? null : event.id()), () -> mapper.fromAgentEvent(event));
    }

    public void captureRetrievalRecord(RetrievalRecord record) {
        capture("retrieval record " + id(record == null ? null : record.id()), () -> mapper.fromRetrievalRecord(record));
    }

    public void captureReviewRecord(ReviewRecord record) {
        capture("review record " + id(record == null ? null : record.id()), () -> mapper.fromReviewRecord(record));
    }

    public void captureReviewIssue(ReviewIssue issue) {
        capture("review issue " + id(issue == null ? null : issue.id()), () -> mapper.fromReviewIssue(issue));
    }

    public void captureReviewFeedback(ReviewIssue issue) {
        capture("review feedback " + id(issue == null ? null : issue.id()), () -> mapper.fromReviewFeedback(issue));
    }

    public void captureDecisionReuseFeedback(DecisionReuseRecord record) {
        capture("decision reuse feedback " + id(record == null ? null : record.id()), () -> mapper.fromDecisionReuseFeedback(record));
    }

    private void capture(String label, Supplier<Observation> supplier) {
        try {
            Observation observation = supplier.get();
            observationRepository.upsertBySourceKey(observation);
        } catch (RuntimeException e) {
            log.warn("Failed to capture observation for {}", label, e);
        }
    }

    private String id(Long id) {
        return id == null ? "unknown" : String.valueOf(id);
    }
}
