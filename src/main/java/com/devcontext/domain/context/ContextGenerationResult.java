package com.devcontext.domain.context;

import java.util.List;

public record ContextGenerationResult(
        Long projectId,
        List<String> generatedFiles,
        List<String> generatedSkippedFiles,
        List<String> manualCreatedFiles,
        List<String> manualSkippedFiles,
        List<String> todos
) {
}
