package com.devcontext.ports.llm;

import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;

public interface LlmClient {

    LlmResponse chat(LlmRequest request);
}

