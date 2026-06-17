package com.devcontext.domain.context;

import java.util.List;

public record ReadOnlyContextProviderTrace(
        Long runId,
        String providerName,
        String operation,
        String status,
        String reason,
        String subject,
        List<String> files,
        int matchBudget,
        int fileBudget,
        int characterBudget,
        int lineBudget,
        int matchesReturned,
        int filesRead,
        int charactersReturned,
        int linesReturned,
        boolean budgetLimited
) {
    public ReadOnlyContextProviderTrace {
        providerName = providerName == null ? "" : providerName;
        operation = operation == null ? "" : operation;
        status = status == null ? "" : status;
        reason = reason == null ? "" : reason;
        subject = subject == null ? "" : subject;
        files = files == null ? List.of() : List.copyOf(files);
    }
}
