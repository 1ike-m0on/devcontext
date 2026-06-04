package com.devcontext.application.decision;

public record UpdateDecisionReuseFeedbackCommand(
        Long recordId,
        String status,
        Boolean accepted,
        String userFeedback
) {
}
