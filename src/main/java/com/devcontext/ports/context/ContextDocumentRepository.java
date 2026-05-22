package com.devcontext.ports.context;

import com.devcontext.domain.context.ContextDocument;
import java.util.List;
import java.util.Optional;

public interface ContextDocumentRepository {

    ContextDocument upsert(ContextDocument document);

    List<ContextDocument> findByProjectId(Long projectId);

    Optional<ContextDocument> findByProjectIdAndPath(Long projectId, String filePath);
}
