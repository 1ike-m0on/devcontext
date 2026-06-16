package com.devcontext.application.llm;

import com.devcontext.common.error.ApiException;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.ports.llm.LlmClient;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class LlmConnectionCheckApplicationService {

    private static final String SUCCESS_CATEGORY = "none";
    private static final String UNKNOWN_CATEGORY = "unknown";
    private static final String CHECK_PROMPT = "Reply with OK only.";

    private final LlmSettingsApplicationService settingsService;
    private final LlmClient llmClient;
    private final DevContextLlmProperties properties;

    public LlmConnectionCheckApplicationService(
            LlmSettingsApplicationService settingsService,
            LlmClient llmClient,
            DevContextLlmProperties properties
    ) {
        this.settingsService = settingsService;
        this.llmClient = llmClient;
        this.properties = properties;
    }

    public LlmConnectionCheckResult testConnection() {
        LlmSettingsApplicationService.LlmSettingsStatus status = settingsService.status();
        if (providerNotConfigured(status)) {
            return failure(
                    status,
                    LlmErrorTypes.PROVIDER_NOT_CONFIGURED,
                    "LLM provider is not configured with a usable provider/key."
            );
        }

        try {
            llmClient.chat(new LlmRequest(CHECK_PROMPT, status.model()));
            return new LlmConnectionCheckResult(
                    status.provider(),
                    status.model(),
                    status.timeout(),
                    true,
                    SUCCESS_CATEGORY,
                    "LLM provider connection check succeeded.",
                    status.keyConfigured(),
                    status.keyStatus()
            );
        } catch (ApiException exception) {
            return failure(status, normalizeFailureCategory(exception.errorCode()), exception.getMessage());
        } catch (RuntimeException exception) {
            return failure(status, classifyMessage(exception.getMessage()), exception.getMessage());
        }
    }

    private boolean providerNotConfigured(LlmSettingsApplicationService.LlmSettingsStatus status) {
        if ("mock".equals(status.provider())) {
            return false;
        }
        return "unsupported_provider".equals(status.status())
                || "missing_key".equals(status.status())
                || !"configured".equals(status.keyStatus());
    }

    private LlmConnectionCheckResult failure(
            LlmSettingsApplicationService.LlmSettingsStatus status,
            String failureCategory,
            String message
    ) {
        return new LlmConnectionCheckResult(
                status.provider(),
                status.model(),
                status.timeout(),
                false,
                normalizeFailureCategory(failureCategory),
                sanitizeMessage(message),
                status.keyConfigured(),
                status.keyStatus()
        );
    }

    private String normalizeFailureCategory(String category) {
        if (category == null || category.isBlank()) {
            return UNKNOWN_CATEGORY;
        }
        return switch (category) {
            case LlmErrorTypes.AUTH_FAILED,
                    LlmErrorTypes.QUOTA_EXCEEDED,
                    LlmErrorTypes.TIMEOUT,
                    LlmErrorTypes.NETWORK_FAILED,
                    LlmErrorTypes.PROXY_REQUIRED,
                    LlmErrorTypes.PARSE_FAILED,
                    LlmErrorTypes.PROVIDER_NOT_CONFIGURED -> category;
            default -> UNKNOWN_CATEGORY;
        };
    }

    private String classifyMessage(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (normalized.contains("api key") || normalized.contains("unauthorized") || normalized.contains("forbidden")) {
            return LlmErrorTypes.AUTH_FAILED;
        }
        if (normalized.contains("quota") || normalized.contains("rate limit") || normalized.contains("429")) {
            return LlmErrorTypes.QUOTA_EXCEEDED;
        }
        if (normalized.contains("timeout") || normalized.contains("timed out")) {
            return LlmErrorTypes.TIMEOUT;
        }
        if (normalized.contains("proxy")) {
            return LlmErrorTypes.PROXY_REQUIRED;
        }
        if (normalized.contains("parse") || normalized.contains("json")) {
            return LlmErrorTypes.PARSE_FAILED;
        }
        if (normalized.contains("not configured") || normalized.contains("unsupported provider")) {
            return LlmErrorTypes.PROVIDER_NOT_CONFIGURED;
        }
        if (normalized.contains("network")
                || normalized.contains("connection")
                || normalized.contains("dns")
                || normalized.contains("resolve")) {
            return LlmErrorTypes.NETWORK_FAILED;
        }
        return UNKNOWN_CATEGORY;
    }

    private String sanitizeMessage(String message) {
        String safe = properties.maskSecrets(message == null || message.isBlank() ? "LLM provider connection check failed." : message);
        safe = safe.replaceAll("(?i)(authorization\\s*:\\s*bearer\\s+)[^\\s,;]+", "$1[masked]");
        safe = safe.replaceAll("(?i)(api[-_ ]?key\\s*[:=]\\s*)[^\\s,;}\\]]+", "$1[masked]");
        safe = safe.replaceAll("(?i)AIza[0-9A-Za-z_-]{16,}", "[masked]");
        safe = safe.replaceAll("(?i)sk-[0-9A-Za-z_-]{16,}", "[masked]");
        safe = safe.replaceAll("\\s+", " ").trim();
        if (safe.length() > 300) {
            return safe.substring(0, 300);
        }
        return safe;
    }

    public record LlmConnectionCheckResult(
            String provider,
            String model,
            String timeout,
            boolean success,
            String failureCategory,
            String messageSummary,
            boolean keyConfigured,
            String keyStatus
    ) {
    }
}
