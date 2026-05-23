package com.devcontext.ports.knowledge;

import com.devcontext.domain.knowledge.VectorDocument;
import com.devcontext.domain.knowledge.VectorQuery;
import com.devcontext.domain.knowledge.VectorSearchHit;
import java.util.List;

public interface VectorStore {

    void upsert(VectorDocument document);

    List<VectorSearchHit> search(VectorQuery query);

    void deleteBySourceId(String collection, Long sourceId);
}
