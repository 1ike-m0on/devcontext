package com.devcontext.application.llm;

import com.devcontext.config.DevContextLlmProperties;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LlmSettingsApplicationService {

    private final DevContextLlmProperties properties;
    private final LlmRuntimeStatus runtimeStatus;

    public LlmSettingsApplicationService(DevContextLlmProperties properties, LlmRuntimeStatus runtimeStatus) {
        this.properties = properties;
        this.runtimeStatus = runtimeStatus;
    }

    public LlmSettingsStatus status() {
        ProviderStatus activeProvider = providerStatus(properties.provider());
        LlmRuntimeStatus.Snapshot runtime = runtimeStatus.snapshot();
        return new LlmSettingsStatus(
                activeProvider.provider(),
                activeProvider.model(),
                activeProvider.status(),
                activeProvider.keyStatus(),
                activeProvider.keyConfigured(),
                runtime.lastCallStatus(),
                runtime.lastErrorType(),
                runtime.lastErrorMessage(),
                runtime.lastCallAt(),
                List.of(
                        providerStatus("mock"),
                        providerStatus("gemini"),
                        providerStatus("deepseek")
                )
        );
    }

    public LlmHealthStatus healthStatus() {
        LlmSettingsStatus status = status();
        return new LlmHealthStatus(
                status.provider(),
                status.model(),
                status.status(),
                status.keyConfigured(),
                status.keyStatus(),
                status.lastErrorType()
        );
    }

    private ProviderStatus providerStatus(String provider) {
        if ("mock".equals(provider)) {
            return new ProviderStatus("mock", properties.mock().model(), "ready", false, false, "not_required");
        }
        if ("gemini".equals(provider)) {
            return keyedProviderStatus("gemini", properties.gemini().model(), properties.gemini().apiKey());
        }
        if ("deepseek".equals(provider)) {
            return keyedProviderStatus("deepseek", properties.deepseek().model(), properties.deepseek().apiKey());
        }
        return new ProviderStatus(provider, properties.modelName(), "unsupported_provider", true, false, "missing");
    }

    private ProviderStatus keyedProviderStatus(String provider, String model, String apiKey) {
        boolean keyConfigured = apiKey != null && !apiKey.isBlank();
        return new ProviderStatus(
                provider,
                model,
                keyConfigured ? "ready" : "missing_key",
                true,
                keyConfigured,
                keyConfigured ? "configured" : "missing"
        );
    }

    public record LlmSettingsStatus(
            String provider,
            String model,
            String status,
            String keyStatus,
            boolean keyConfigured,
            String lastCallStatus,
            String lastErrorType,
            String lastErrorMessage,
            Instant lastCallAt,
            List<ProviderStatus> supportedProviders
    ) {
    }

    public record ProviderStatus(
            String provider,
            String model,
            String status,
            boolean keyRequired,
            boolean keyConfigured,
            String keyStatus
    ) {
    }

    public record LlmHealthStatus(
            String provider,
            String model,
            String status,
            boolean keyConfigured,
            String keyStatus,
            String lastErrorType
    ) {
    }
}
