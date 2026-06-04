package com.devcontext.adapters.llm;

import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.ports.llm.LlmClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "devcontext.llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MockLlmClient.class);

    public MockLlmClient() {
        log.info("Mock LLM client initialized");
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        log.info("Mock LLM client handling request with model {}", request.modelName());
        String content = "MOCK_RESPONSE: " + request.prompt();
        return new LlmResponse(
                content,
                request.modelName(),
                estimateTokens(request.prompt()),
                estimateTokens(content)
        );
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }
}
