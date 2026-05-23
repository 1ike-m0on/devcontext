package com.devcontext.application.knowledge;

public record CreateKnowledgeSourceCommand(
        String name,
        String rootPath,
        String sourceType
) {
}
