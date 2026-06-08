package com.devcontext.adapters.llm;

import com.devcontext.application.llm.LlmErrorTypes;
import com.devcontext.application.llm.LlmRuntimeStatus;
import com.devcontext.common.error.ApiException;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.ports.llm.LlmClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(LlmClient.class)
public class UnsupportedProviderLlmClient implements LlmClient {

    private final DevContextLlmProperties properties;
    private final LlmRuntimeStatus runtimeStatus;

    public UnsupportedProviderLlmClient(DevContextLlmProperties properties, LlmRuntimeStatus runtimeStatus) {
        this.properties = properties;
        this.runtimeStatus = runtimeStatus;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        String message = "LLM provider is not supported: " + properties.provider();
        runtimeStatus.recordFailure(LlmErrorTypes.PROVIDER_NOT_CONFIGURED, message);
        throw new ApiException(LlmErrorTypes.PROVIDER_NOT_CONFIGURED, message, HttpStatus.BAD_REQUEST);
    }
}
