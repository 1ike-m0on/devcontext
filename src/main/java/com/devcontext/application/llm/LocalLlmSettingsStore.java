package com.devcontext.application.llm;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

@Component
public class LocalLlmSettingsStore {

    private final Path root;
    private final Path configPath;
    private final Yaml yaml;

    public LocalLlmSettingsStore(@Value("${devcontext.local-config-root:.}") String localConfigRoot) {
        this.root = Path.of(localConfigRoot).toAbsolutePath().normalize();
        this.configPath = root.resolve("config").resolve("devcontext.local.yml").normalize();
        if (!configPath.startsWith(root)) {
            throw new IllegalArgumentException("Local config path must stay under the configured root");
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        this.yaml = new Yaml(options);
    }

    public PendingConfig pendingConfig() {
        Map<String, Object> rootNode = readRoot();
        Map<String, Object> llmNode = childMap(childMap(rootNode, "devcontext"), "llm");
        String provider = stringValue(llmNode.get("provider"));
        if (provider == null) {
            return PendingConfig.empty(configPath);
        }

        String normalizedProvider = normalizeProvider(provider);
        Map<String, Object> providerNode = childMap(llmNode, normalizedProvider);
        return new PendingConfig(
                normalizedProvider,
                stringValue(providerNode.get("model")),
                stringValue(providerNode.get("api-key")),
                configPath
        );
    }

    public void save(SaveCommand command) {
        Map<String, Object> rootNode = readRoot();
        Map<String, Object> devcontext = mutableChildMap(rootNode, "devcontext");
        Map<String, Object> llm = mutableChildMap(devcontext, "llm");
        String provider = normalizeProvider(command.provider());
        llm.put("provider", provider);

        Map<String, Object> providerNode = mutableChildMap(llm, provider);
        providerNode.put("model", command.model());
        if (isKeyedProvider(provider) && command.apiKey() != null && !command.apiKey().isBlank()) {
            providerNode.put("api-key", command.apiKey());
        }

        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                yaml.dump(rootNode, writer);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write local LLM settings to " + configPath, exception);
        }
    }

    public String configPath() {
        return configPath.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRoot() {
        if (!Files.exists(configPath)) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            Object loaded = yaml.load(reader);
            if (loaded instanceof Map<?, ?> loadedMap) {
                Map<String, Object> result = new LinkedHashMap<>();
                loadedMap.forEach((key, value) -> {
                    if (key != null) {
                        result.put(key.toString(), value);
                    }
                });
                return result;
            }
            return new LinkedHashMap<>();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read local LLM settings from " + configPath, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableChildMap(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        if (existing instanceof Map<?, ?> existingMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            existingMap.forEach((childKey, value) -> {
                if (childKey != null) {
                    result.put(childKey.toString(), value);
                }
            });
            parent.put(key, result);
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        parent.put(key, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        if (existing instanceof Map<?, ?> existingMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            existingMap.forEach((childKey, value) -> {
                if (childKey != null) {
                    result.put(childKey.toString(), value);
                }
            });
            return result;
        }
        return Map.of();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "mock";
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isKeyedProvider(String provider) {
        return "gemini".equals(provider) || "deepseek".equals(provider);
    }

    public record SaveCommand(
            String provider,
            String model,
            String apiKey
    ) {

        @Override
        public String toString() {
            return "SaveCommand[provider=" + provider + ", model=" + model + ", apiKey=[masked]]";
        }
    }

    public record PendingConfig(
            String provider,
            String model,
            String apiKey,
            Path path
    ) {

        static PendingConfig empty(Path path) {
            return new PendingConfig(null, null, null, path);
        }

        @Override
        public String toString() {
            return "PendingConfig[provider=" + provider + ", model=" + model + ", apiKey=[masked], path=" + path + "]";
        }
    }
}
