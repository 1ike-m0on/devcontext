package com.devcontext.application.llm;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class LlmRuntimeStatus {

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(
            new Snapshot("never_called", null, null, null)
    );

    public void recordSuccess() {
        snapshot.set(new Snapshot("success", null, null, Instant.now()));
    }

    public void recordFailure(String errorType, String errorMessage) {
        snapshot.set(new Snapshot("failed", errorType, errorMessage, Instant.now()));
    }

    public Snapshot snapshot() {
        return snapshot.get();
    }

    public record Snapshot(
            String lastCallStatus,
            String lastErrorType,
            String lastErrorMessage,
            Instant lastCallAt
    ) {
    }
}
