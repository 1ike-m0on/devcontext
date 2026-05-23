package com.devcontext.ports.knowledge;

import com.devcontext.domain.knowledge.KnowledgeDocument;
import java.util.List;

public interface KnowledgeDocumentRepository {

    KnowledgeDocument save(KnowledgeDocument document);

    List<KnowledgeDocument> findBySourceId(Long sourceId);

    void deleteBySourceId(Long sourceId);
}
