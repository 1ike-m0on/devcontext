package com.devcontext.adapters.web;

import com.devcontext.application.memory.ObservationApplicationService;
import com.devcontext.common.api.ApiResponse;
import com.devcontext.domain.memory.Observation;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ObservationController {

    private final ObservationApplicationService observationService;

    public ObservationController(ObservationApplicationService observationService) {
        this.observationService = observationService;
    }

    @GetMapping("/api/observations")
    public ApiResponse<List<Observation>> listObservations(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String lifecycle,
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) Long reviewId,
            @RequestParam(required = false) Long retrievalId,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.ok(observationService.listObservations(
                projectId,
                taskType,
                lifecycle,
                runId,
                reviewId,
                retrievalId,
                limit == null ? 30 : limit
        ));
    }

    @GetMapping("/api/observations/{observationId}")
    public ApiResponse<Observation> getObservation(@PathVariable Long observationId) {
        return ApiResponse.ok(observationService.getObservation(observationId));
    }

    @PatchMapping("/api/observations/{observationId}/lifecycle")
    public ApiResponse<Observation> updateLifecycle(
            @PathVariable Long observationId,
            @RequestBody(required = false) UpdateLifecycleRequest request
    ) {
        return ApiResponse.ok(observationService.updateLifecycle(
                observationId,
                request == null ? null : request.lifecycle()
        ));
    }

    @GetMapping("/api/agent-runs/{runId}/observations")
    public ApiResponse<List<Observation>> listRunObservations(@PathVariable Long runId) {
        return ApiResponse.ok(observationService.listRunObservations(runId));
    }

    public record UpdateLifecycleRequest(
            String lifecycle
    ) {
    }
}
