package com.devcontext.ports.knowledge;

import com.devcontext.domain.knowledge.EmbeddingVector;

public interface EmbeddingClient {

    EmbeddingVector embed(String text);
}
