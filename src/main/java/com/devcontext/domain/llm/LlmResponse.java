package com.devcontext.domain.llm;

public record LlmResponse(
        String content,
        String modelName,
        int inputTokenEstimate,
        int outputTokenEstimate
) {
}

