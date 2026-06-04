package com.devcontext.application.review;

import java.util.List;

public record CreateReviewCommand(
        String sourceType,
        String baseBranch,
        String compareBranch,
        String diffText,
        String mode,
        List<String> selectedFiles
) {
    public CreateReviewCommand(String sourceType, String baseBranch, String compareBranch, String diffText, String mode) {
        this(sourceType, baseBranch, compareBranch, diffText, mode, List.of());
    }
}
