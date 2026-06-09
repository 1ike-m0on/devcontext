package com.devcontext.application.llm;

import com.devcontext.config.DevContextLlmProperties;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class LlmSettingsApplicationService {

    private final DevContextLlmProperties properties;
    private final LlmRuntimeStatus runtimeStatus;
    private final LocalLlmSettingsStore localSettingsStore;

    public LlmSettingsApplicationService(
            DevContextLlmProperties properties,
            LlmRuntimeStatus runtimeStatus,
            LocalLlmSettingsStore localSettingsStore
    ) {
        this.properties = properties;
        this.runtimeStatus = runtimeStatus;
        this.localSettingsStore = localSettingsStore;
    }

    public LlmSettingsStatus status() {
        ProviderStatus activeProvider = providerStatus(properties.provider());
        LlmRuntimeStatus.Snapshot runtime = runtimeStatus.snapshot();
        LocalLlmSettingsStore.PendingConfig pendingConfig = localSettingsStore.pendingConfig();
        PendingProviderStatus pending = pendingStatus(pendingConfig);
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
                restartRequired(pendingConfig),
                pending,
                localSettingsStore.configPath(),
                List.of(
                        providerStatus("mock"),
                        providerStatus("gemini"),
                        providerStatus("deepseek")
                )
        );
    }

    public LlmSettingsStatus update(UpdateCommand command) {
        String provider = normalizeProvider(command.provider());
        if (!properties.supportedProviders().contains(provider)) {
            throw new IllegalArgumentException("Unsupported LLM provider");
        }

        String model = normalizeValue(command.model(), modelForProvider(provider));
        String apiKey = normalizeValue(command.apiKey(), null);
        localSettingsStore.save(new LocalLlmSettingsStore.SaveCommand(provider, model, apiKey));
        return status();
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

    private PendingProviderStatus pendingStatus(LocalLlmSettingsStore.PendingConfig pending) {
        if (pending.provider() == null) {
            return null;
        }
        String model = normalizeValue(pending.model(), modelForProvider(pending.provider()));
        boolean keyRequired = isKeyedProvider(pending.provider());
        boolean keyConfigured = keyRequired
                && (hasText(pending.apiKey())
                || (pending.provider().equals(properties.provider()) && hasText(activeApiKey(pending.provider()))));
        boolean missingRequiredKey = keyRequired && !keyConfigured;
        return new PendingProviderStatus(
                pending.provider(),
                model,
                missingRequiredKey ? "missing_key" : "ready",
                keyConfigured,
                keyRequired ? (missingRequiredKey ? "missing" : "configured") : "not_required",
                pending.path().toString()
        );
    }

    private boolean restartRequired(LocalLlmSettingsStore.PendingConfig pending) {
        if (pending.provider() == null) {
            return false;
        }
        String provider = normalizeProvider(pending.provider());
        if (!provider.equals(properties.provider())) {
            return true;
        }
        String model = normalizeValue(pending.model(), modelForProvider(provider));
        if (!model.equals(modelForProvider(provider))) {
            return true;
        }
        return isKeyedProvider(provider)
                && hasText(pending.apiKey())
                && !pending.apiKey().equals(activeApiKey(provider));
    }

    private String modelForProvider(String provider) {
        if ("gemini".equals(provider)) {
            return properties.gemini().model();
        }
        if ("deepseek".equals(provider)) {
            return properties.deepseek().model();
        }
        return properties.mock().model();
    }

    private String activeApiKey(String provider) {
        if ("gemini".equals(provider)) {
            return properties.gemini().apiKey();
        }
        if ("deepseek".equals(provider)) {
            return properties.deepseek().apiKey();
        }
        return null;
    }

    private boolean isKeyedProvider(String provider) {
        return "gemini".equals(provider) || "deepseek".equals(provider);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return properties.provider();
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
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
            boolean restartRequired,
            PendingProviderStatus pending,
            String localConfigPath,
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

    public record PendingProviderStatus(
            String provider,
            String model,
            String status,
            boolean keyConfigured,
            String keyStatus,
            String localConfigPath
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

    public record UpdateCommand(
            String provider,
            String model,
            String apiKey
    ) {

        @Override
        public String toString() {
            return "UpdateCommand[provider=" + provider + ", model=" + model + ", apiKey=[masked]]";
        }
    }
}
