package com.devcontext.domain.llm;

public record LlmRequest(
        String prompt,
        String modelName
) {
}

