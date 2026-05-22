package com.devcontext.adapters.llm;

import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.ports.llm.LlmClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "devcontext.llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    @Override
    public LlmResponse chat(LlmRequest request) {
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
