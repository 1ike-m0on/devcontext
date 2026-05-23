package com.devcontext.domain.knowledge;

public record KnowledgeChunkView(
        KnowledgeChunk chunk,
        KnowledgeDocument document,
        KnowledgeSource source
) {
}
