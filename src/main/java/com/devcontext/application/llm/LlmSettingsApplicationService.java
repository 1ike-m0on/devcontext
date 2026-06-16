package com.devcontext.application.llm;

import com.devcontext.config.DevContextLlmProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class LlmSettingsApplicationService {

    private static final Pattern SIMPLE_DURATION_PATTERN = Pattern.compile("^(\\d+)(ms|s|m|h)?$");

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
                activeProvider.timeout(),
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
        String timeout = normalizeTimeout(command.timeout(), timeoutForProvider(provider));
        String apiKey = normalizeValue(command.apiKey(), null);
        localSettingsStore.save(new LocalLlmSettingsStore.SaveCommand(provider, model, apiKey, timeout));
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
            return new ProviderStatus("mock", properties.mock().model(), null, "ready", false, false, "not_required");
        }
        if ("gemini".equals(provider)) {
            return keyedProviderStatus("gemini", properties.gemini().model(), properties.gemini().apiKey(), properties.gemini().timeout());
        }
        if ("deepseek".equals(provider)) {
            return keyedProviderStatus("deepseek", properties.deepseek().model(), properties.deepseek().apiKey(), properties.deepseek().timeout());
        }
        return new ProviderStatus(provider, properties.modelName(), null, "unsupported_provider", true, false, "missing");
    }

    private ProviderStatus keyedProviderStatus(String provider, String model, String apiKey, Duration timeout) {
        boolean keyConfigured = apiKey != null && !apiKey.isBlank();
        return new ProviderStatus(
                provider,
                model,
                formatDuration(timeout),
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
        String timeout = normalizeTimeout(pending.timeout(), timeoutForProvider(pending.provider()));
        boolean keyRequired = isKeyedProvider(pending.provider());
        boolean keyConfigured = keyRequired
                && (hasText(pending.apiKey())
                || (pending.provider().equals(properties.provider()) && hasText(activeApiKey(pending.provider()))));
        boolean missingRequiredKey = keyRequired && !keyConfigured;
        return new PendingProviderStatus(
                pending.provider(),
                model,
                timeout,
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
        String timeout = normalizeTimeout(pending.timeout(), timeoutForProvider(provider));
        if (isKeyedProvider(provider) && !timeout.equals(timeoutForProvider(provider))) {
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

    private String timeoutForProvider(String provider) {
        if ("gemini".equals(provider)) {
            return formatDuration(properties.gemini().timeout());
        }
        if ("deepseek".equals(provider)) {
            return formatDuration(properties.deepseek().timeout());
        }
        return null;
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

    private String normalizeTimeout(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return formatDuration(parseTimeout(value));
    }

    private Duration parseTimeout(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("P") || trimmed.startsWith("p")) {
            Duration duration = Duration.parse(trimmed.toUpperCase(Locale.ROOT));
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("LLM provider timeout must be greater than 0");
            }
            return duration;
        }

        Matcher matcher = SIMPLE_DURATION_PATTERN.matcher(trimmed.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("LLM provider timeout must use seconds, ms, m, h, or ISO-8601 duration");
        }
        long amount = Long.parseLong(matcher.group(1));
        if (amount <= 0) {
            throw new IllegalArgumentException("LLM provider timeout must be greater than 0");
        }
        String unit = matcher.group(2);
        if ("ms".equals(unit)) {
            return Duration.ofMillis(amount);
        }
        if ("m".equals(unit)) {
            return Duration.ofMinutes(amount);
        }
        if ("h".equals(unit)) {
            return Duration.ofHours(amount);
        }
        return Duration.ofSeconds(amount);
    }

    private String formatDuration(Duration duration) {
        if (duration == null) {
            return null;
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("LLM provider timeout must be greater than 0");
        }
        long millis = duration.toMillis();
        if (millis % 1000 == 0) {
            return duration.toSeconds() + "s";
        }
        return millis + "ms";
    }

    public record LlmSettingsStatus(
            String provider,
            String model,
            String timeout,
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
            String timeout,
            String status,
            boolean keyRequired,
            boolean keyConfigured,
            String keyStatus
    ) {
    }

    public record PendingProviderStatus(
            String provider,
            String model,
            String timeout,
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
            String apiKey,
            String timeout
    ) {

        @Override
        public String toString() {
            return "UpdateCommand[provider=" + provider + ", model=" + model + ", apiKey=[masked], timeout=" + timeout + "]";
        }
    }
}
