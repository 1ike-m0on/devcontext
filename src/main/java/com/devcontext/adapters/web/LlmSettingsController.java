package com.devcontext.adapters.web;

import com.devcontext.application.llm.LlmConnectionCheckApplicationService;
import com.devcontext.application.llm.LlmConnectionCheckApplicationService.LlmConnectionCheckResult;
import com.devcontext.application.llm.LlmSettingsApplicationService;
import com.devcontext.application.llm.LlmSettingsApplicationService.LlmSettingsStatus;
import com.devcontext.common.api.ApiResponse;
import com.devcontext.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/llm")
public class LlmSettingsController {

    private final LlmSettingsApplicationService settingsService;
    private final LlmConnectionCheckApplicationService connectionCheckService;

    public LlmSettingsController(
            LlmSettingsApplicationService settingsService,
            LlmConnectionCheckApplicationService connectionCheckService
    ) {
        this.settingsService = settingsService;
        this.connectionCheckService = connectionCheckService;
    }

    @GetMapping
    public ApiResponse<LlmSettingsStatus> status() {
        return ApiResponse.ok(settingsService.status());
    }

    @PutMapping
    public ApiResponse<LlmSettingsStatus> update(@RequestBody(required = false) LlmSettingsUpdateRequest request) {
        LlmSettingsUpdateRequest safeRequest = request == null ? LlmSettingsUpdateRequest.empty() : request;
        try {
            return ApiResponse.ok(settingsService.update(safeRequest.toCommand()));
        } catch (IllegalArgumentException exception) {
            throw new ApiException("INVALID_LLM_SETTINGS", exception.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    @PostMapping("/test")
    public ApiResponse<LlmConnectionCheckResult> testConnection() {
        return ApiResponse.ok(connectionCheckService.testConnection());
    }

    public record LlmSettingsUpdateRequest(
            String provider,
            String model,
            String apiKey,
            String geminiApiKey,
            String deepseekApiKey,
            String timeout,
            String geminiTimeout,
            String deepseekTimeout
    ) {

        static LlmSettingsUpdateRequest empty() {
            return new LlmSettingsUpdateRequest(null, null, null, null, null, null, null, null);
        }

        LlmSettingsApplicationService.UpdateCommand toCommand() {
            return new LlmSettingsApplicationService.UpdateCommand(provider, model, selectedApiKey(), selectedTimeout());
        }

        private String selectedApiKey() {
            if ("gemini".equalsIgnoreCase(provider) && hasText(geminiApiKey)) {
                return geminiApiKey;
            }
            if ("deepseek".equalsIgnoreCase(provider) && hasText(deepseekApiKey)) {
                return deepseekApiKey;
            }
            return apiKey;
        }

        private String selectedTimeout() {
            if ("gemini".equalsIgnoreCase(provider) && hasText(geminiTimeout)) {
                return geminiTimeout;
            }
            if ("deepseek".equalsIgnoreCase(provider) && hasText(deepseekTimeout)) {
                return deepseekTimeout;
            }
            return timeout;
        }

        private boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
