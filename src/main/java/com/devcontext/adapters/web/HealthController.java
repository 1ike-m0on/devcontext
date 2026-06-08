package com.devcontext.adapters.web;

import com.devcontext.application.llm.LlmSettingsApplicationService;
import com.devcontext.common.api.ApiResponse;
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

    private final LlmSettingsApplicationService llmSettingsService;
    private final DevContextVectorProperties vectorProperties;
    private final LlmClient llmClient;

    public HealthController(
            LlmSettingsApplicationService llmSettingsService,
            DevContextVectorProperties vectorProperties,
            LlmClient llmClient
    ) {
        this.llmSettingsService = llmSettingsService;
        this.vectorProperties = vectorProperties;
        this.llmClient = llmClient;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> health() {
        LlmSettingsApplicationService.LlmHealthStatus llm = llmSettingsService.healthStatus();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "DevContext");
        data.put("status", "UP");
        data.put("llm", llm);
        data.put("llmProvider", llm.provider());
        data.put("llmModel", llm.model());
        data.put("llmClient", llmClient.getClass().getSimpleName());
        data.put("llmStatus", llm.status());
        data.put("llmKeyConfigured", llm.keyConfigured());
        data.put("llmKeyStatus", llm.keyStatus());
        data.put("llmLastErrorType", llm.lastErrorType());
        data.put("vectorProvider", vectorProperties.provider());
        data.put("timestamp", Instant.now().toString());
        return ApiResponse.ok(data);
    }
}
