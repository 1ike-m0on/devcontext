package com.devcontext.adapters.web;

import com.devcontext.common.api.ApiResponse;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.config.DevContextVectorProperties;
import com.devcontext.ports.llm.LlmClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final DevContextLlmProperties llmProperties;
    private final DevContextVectorProperties vectorProperties;
    private final LlmClient llmClient;

    public HealthController(
            DevContextLlmProperties llmProperties,
            DevContextVectorProperties vectorProperties,
            LlmClient llmClient
    ) {
        this.llmProperties = llmProperties;
        this.vectorProperties = vectorProperties;
        this.llmClient = llmClient;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "DevContext");
        data.put("status", "UP");
        data.put("llmProvider", llmProperties.provider());
        data.put("llmModel", llmProperties.modelName());
        data.put("llmClient", llmClient.getClass().getSimpleName());
        data.put("geminiApiKeyConfigured", llmProperties.gemini().apiKey() != null && !llmProperties.gemini().apiKey().isBlank());
        data.put("geminiBaseUrl", llmProperties.gemini().baseUrl());
        data.put("geminiTimeout", llmProperties.gemini().timeout().toString());
        data.put("deepseekApiKeyConfigured", llmProperties.deepseek().apiKey() != null && !llmProperties.deepseek().apiKey().isBlank());
        data.put("deepseekBaseUrl", llmProperties.deepseek().baseUrl());
        data.put("deepseekTimeout", llmProperties.deepseek().timeout().toString());
        data.put("vectorProvider", vectorProperties.provider());
        data.put("timestamp", Instant.now().toString());
        return ApiResponse.ok(data);
    }
}
