package com.devcontext.ports.knowledge;

import com.devcontext.domain.knowledge.KnowledgeChunk;
import com.devcontext.domain.knowledge.KnowledgeChunkView;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface KnowledgeChunkRepository {

    KnowledgeChunk save(KnowledgeChunk chunk);

    List<KnowledgeChunkView> findAllViews();

    List<KnowledgeChunkView> findViewsBySourceId(Long sourceId);

    Optional<KnowledgeChunkView> findViewByChunkId(Long chunkId);

    Map<Long, KnowledgeChunkView> findViewsByChunkIds(Collection<Long> chunkIds);

    Map<String, KnowledgeChunkView> findViewsByVectorIds(Collection<String> vectorIds);

    void deleteBySourceId(Long sourceId);
}
