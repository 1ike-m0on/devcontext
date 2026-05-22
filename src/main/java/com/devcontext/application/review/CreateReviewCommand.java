package com.devcontext.application.review;

public record CreateReviewCommand(
        String baseBranch,
        String compareBranch,
        String diffText,
        String mode
) {
}
