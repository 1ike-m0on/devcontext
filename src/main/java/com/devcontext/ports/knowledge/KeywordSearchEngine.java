package com.devcontext.ports.knowledge;

import com.devcontext.domain.knowledge.KeywordSearchHit;
import java.util.List;

public interface KeywordSearchEngine {

    List<KeywordSearchHit> search(String query, Long sourceId, int topK);
}
