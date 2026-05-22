package com.devcontext.context;

public record ContextRequest(
        Long projectId,
        String taskType
) {
}

