package com.devcontext.adapters.web;

import com.devcontext.application.llm.LlmSettingsApplicationService;
import com.devcontext.application.llm.LlmSettingsApplicationService.LlmSettingsStatus;
import com.devcontext.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/llm")
public class LlmSettingsController {

    private final LlmSettingsApplicationService settingsService;

    public LlmSettingsController(LlmSettingsApplicationService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public ApiResponse<LlmSettingsStatus> status() {
        return ApiResponse.ok(settingsService.status());
    }
}
