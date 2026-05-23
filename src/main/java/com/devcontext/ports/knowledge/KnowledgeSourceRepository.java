package com.devcontext.ports.knowledge;

import com.devcontext.domain.knowledge.KnowledgeSource;
import java.util.List;
import java.util.Optional;

public interface KnowledgeSourceRepository {

    KnowledgeSource save(KnowledgeSource source);

    KnowledgeSource update(KnowledgeSource source);

    Optional<KnowledgeSource> findById(Long sourceId);

    List<KnowledgeSource> findAll();
}
