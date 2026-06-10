package com.devcontext.domain.evidence;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public enum EvidenceType {
    GENERATED_DOC(
            EvidenceSourceKind.DOCUMENTATION,
            EvidenceSourceReliability.DERIVED,
            "generated_doc",
            "generated-documentation",
            "generated_documentation",
            "ai_doc",
            "ai_generated_doc"
    ),
    MANUAL_DOC(
            EvidenceSourceKind.DOCUMENTATION,
            EvidenceSourceReliability.SECONDARY,
            "manual_doc",
            "manual-documentation",
            "manual_documentation",
            "human_doc",
            "readme"
    ),
    CODE_MAP(
            EvidenceSourceKind.CODE_STRUCTURE,
            EvidenceSourceReliability.DERIVED,
            "code_map",
            "codemap",
            "code-structure",
            "code_structure",
            "architecture_map"
    ),
    SQL_SCHEMA(
            EvidenceSourceKind.DATA_SCHEMA,
            EvidenceSourceReliability.PRIMARY,
            "sql_schema",
            "database_schema",
            "db_schema",
            "schema",
            "migration",
            "ddl"
    ),
    MAPPER(
            EvidenceSourceKind.DATA_ACCESS,
            EvidenceSourceReliability.PRIMARY,
            "mapper",
            "sql_mapper",
            "mybatis_mapper",
            "data_mapper",
            "dao_mapper"
    ),
    CONFIG(
            EvidenceSourceKind.CONFIGURATION,
            EvidenceSourceReliability.PRIMARY,
            "config",
            "configuration",
            "application_config",
            "properties",
            "yaml_config"
    ),
    DEPLOYMENT(
            EvidenceSourceKind.DEPLOYMENT,
            EvidenceSourceReliability.PRIMARY,
            "deployment",
            "deploy",
            "docker",
            "compose",
            "kubernetes",
            "k8s",
            "helm"
    ),
    OBSERVABILITY(
            EvidenceSourceKind.OBSERVABILITY,
            EvidenceSourceReliability.PRIMARY,
            "observability",
            "monitoring",
            "metrics",
            "prometheus",
            "grafana",
            "actuator",
            "telemetry"
    ),
    TEST(
            EvidenceSourceKind.TEST_ARTIFACT,
            EvidenceSourceReliability.PRIMARY,
            "test",
            "tests",
            "testing",
            "test_case",
            "unit_test",
            "integration_test",
            "fixture"
    ),
    BENCHMARK(
            EvidenceSourceKind.BENCHMARK_REPORT,
            EvidenceSourceReliability.PRIMARY,
            "benchmark",
            "benchmark_report",
            "performance_report",
            "load_test",
            "jmeter",
            "k6",
            "perf"
    ),
    CI(
            EvidenceSourceKind.CI_PIPELINE,
            EvidenceSourceReliability.PRIMARY,
            "ci",
            "continuous_integration",
            "github_actions",
            "workflow",
            "pipeline"
    ),
    API_CONTROLLER(
            EvidenceSourceKind.API_SURFACE,
            EvidenceSourceReliability.PRIMARY,
            "api_controller",
            "controller",
            "rest_controller",
            "endpoint",
            "api_surface"
    ),
    SERVICE_CODE(
            EvidenceSourceKind.SOURCE_CODE,
            EvidenceSourceReliability.PRIMARY,
            "service_code",
            "service",
            "application_service",
            "handler",
            "use_case",
            "usecase"
    ),
    QUEUE(
            EvidenceSourceKind.MESSAGE_QUEUE,
            EvidenceSourceReliability.PRIMARY,
            "queue",
            "message_queue",
            "mq",
            "rocketmq",
            "kafka",
            "rabbitmq",
            "consumer",
            "producer"
    ),
    CACHE(
            EvidenceSourceKind.CACHE,
            EvidenceSourceReliability.PRIMARY,
            "cache",
            "redis",
            "lua_cache",
            "redis_lua",
            "caffeine",
            "invalidation"
    ),
    SECURITY(
            EvidenceSourceKind.SECURITY,
            EvidenceSourceReliability.PRIMARY,
            "security",
            "auth",
            "authentication",
            "authorization",
            "permission",
            "privacy",
            "token",
            "jwt"
    );

    private static final Map<String, EvidenceType> LOOKUP = buildLookup();

    private final EvidenceSourceKind sourceKind;
    private final EvidenceSourceReliability sourceReliability;
    private final String[] aliases;

    EvidenceType(
            EvidenceSourceKind sourceKind,
            EvidenceSourceReliability sourceReliability,
            String... aliases
    ) {
        this.sourceKind = sourceKind;
        this.sourceReliability = sourceReliability;
        this.aliases = aliases;
    }

    public String canonicalName() {
        return name();
    }

    public EvidenceSourceKind sourceKind() {
        return sourceKind;
    }

    public EvidenceSourceReliability sourceReliability() {
        return sourceReliability;
    }

    public static Optional<EvidenceType> normalize(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(LOOKUP.get(normalizeKey(value)));
    }

    public static EvidenceType normalizeOrDefault(String value, EvidenceType fallback) {
        return normalize(value).orElse(fallback);
    }

    static String normalizeKey(String value) {
        StringBuilder builder = new StringBuilder();
        char previous = 0;
        boolean lastWasSeparator = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isUpperCase(current) && Character.isLowerCase(previous) && !lastWasSeparator) {
                builder.append('_');
            }
            if (Character.isLetterOrDigit(current)) {
                builder.append(Character.toLowerCase(current));
                lastWasSeparator = false;
            } else if (builder.length() > 0 && !lastWasSeparator) {
                builder.append('_');
                lastWasSeparator = true;
            }
            previous = current;
        }
        int length = builder.length();
        if (length > 0 && builder.charAt(length - 1) == '_') {
            builder.deleteCharAt(length - 1);
        }
        return builder.toString();
    }

    private static Map<String, EvidenceType> buildLookup() {
        Map<String, EvidenceType> lookup = new LinkedHashMap<>();
        for (EvidenceType type : values()) {
            register(lookup, type.name(), type);
            Arrays.stream(type.aliases).forEach(alias -> register(lookup, alias, type));
        }
        return Map.copyOf(lookup);
    }

    private static void register(Map<String, EvidenceType> lookup, String value, EvidenceType type) {
        String key = normalizeKey(value);
        if (!key.isBlank()) {
            lookup.putIfAbsent(key, type);
        }
    }
}
