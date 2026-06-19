package com.devcontext.application.knowledge;

import com.devcontext.application.context.QueryTermNormalizer;
import com.devcontext.domain.knowledge.KnowledgeEvidenceType;
import com.devcontext.domain.knowledge.KnowledgeQueryPlan;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeQueryPlanner {

    private final QueryTermNormalizer queryTermNormalizer;

    public KnowledgeQueryPlanner(QueryTermNormalizer queryTermNormalizer) {
        this.queryTermNormalizer = queryTermNormalizer;
    }

    public KnowledgeQueryPlan plan(String query) {
        String normalizedQuery = normalizeWhitespace(query);
        LinkedHashSet<String> normalizedTermSet = new LinkedHashSet<>(queryTermNormalizer.normalize(query));
        String rewrittenQuery = rewrite(normalizedQuery, normalizedTermSet.stream().toList());

        Set<String> rawTokens = tokenizeRaw(normalizedQuery);
        Set<String> tokens = new LinkedHashSet<>();
        tokens.add(normalizedQuery.toLowerCase(Locale.ROOT));
        normalizedTermSet.stream()
                .map(term -> term.toLowerCase(Locale.ROOT))
                .forEach(tokens::add);

        LinkedHashSet<KnowledgeEvidenceType> preferred = new LinkedHashSet<>();
        LinkedHashSet<KnowledgeEvidenceType> required = new LinkedHashSet<>();
        LinkedHashSet<String> planningReasons = new LinkedHashSet<>();
        String intent = "project_overview";

        if (matches(tokens, "database-schema", "sql", "index", "schema", "database", "mapper", "mybatis", "索引", "数据库")) {
            intent = "database_detail";
            planningReasons.add("database_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.SQL_SCHEMA,
                    KnowledgeEvidenceType.MAPPER,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.CONFIG
            );
        }
        if (matches(tokens, "configuration", "config", "properties", "yaml", "yml", "profile", "port", "配置")) {
            if ("project_overview".equals(intent)) {
                intent = "configuration_detail";
            }
            planningReasons.add("configuration_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.CONFIG,
                    KnowledgeEvidenceType.DEPLOYMENT,
                    KnowledgeEvidenceType.GENERATED_DOC
            );
        }
        if (matches(tokens, "deployment", "docker", "compose", "kubernetes", "deploy", "部署", "启动")) {
            if ("project_overview".equals(intent) || "configuration_detail".equals(intent)) {
                intent = "deployment_detail";
            }
            planningReasons.add("deployment_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.DEPLOYMENT,
                    KnowledgeEvidenceType.CONFIG,
                    KnowledgeEvidenceType.GENERATED_DOC,
                    KnowledgeEvidenceType.MANUAL_DOC
            );
        }
        if (matches(tokens, "observability", "monitoring", "metrics", "metric", "prometheus", "grafana", "actuator", "dashboard", "监控", "指标")) {
            intent = "observability_detail";
            planningReasons.add("observability_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.OBSERVABILITY,
                    KnowledgeEvidenceType.DEPLOYMENT,
                    KnowledgeEvidenceType.CONFIG,
                    KnowledgeEvidenceType.SERVICE_CODE
            );
        }
        if (matches(tokens, "test", "testing", "junit", "mock", "coverage", "fixture", "测试")) {
            if ("project_overview".equals(intent)) {
                intent = "test_strategy";
            }
            planningReasons.add("test_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.TEST,
                    KnowledgeEvidenceType.BENCHMARK,
                    KnowledgeEvidenceType.SERVICE_CODE
            );
        }
        if (matches(tokens, "benchmark", "performance", "qps", "p95", "p99", "latency", "throughput", "性能", "压测")) {
            intent = "performance_result";
            planningReasons.add("performance_terms_detected");
            required.add(KnowledgeEvidenceType.BENCHMARK);
            addPreferred(preferred,
                    KnowledgeEvidenceType.BENCHMARK,
                    KnowledgeEvidenceType.OBSERVABILITY,
                    KnowledgeEvidenceType.DEPLOYMENT
            );
        }
        if (matches(tokens, "code-structure", "controller", "service", "repository", "dao", "entity", "api", "endpoint", "实现", "调用链")) {
            if ("project_overview".equals(intent)) {
                intent = "implementation_detail";
            }
            planningReasons.add("implementation_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.CODE_MAP,
                    KnowledgeEvidenceType.API_CONTROLLER,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.MAPPER
            );
        }
        if (matches(tokens, "stock-deduction", "redis-lua", "redis", "lua", "cache", "invalidation", "缓存", "库存", "扣减")) {
            intent = "cache_detail";
            planningReasons.add("cache_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.CACHE,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.CONFIG
            );
        }
        if (matches(tokens, "message-queue", "queue", "mq", "rocketmq", "kafka", "consumer", "producer", "队列", "异步")) {
            intent = "message_queue_detail";
            planningReasons.add("message_queue_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.QUEUE,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.CONFIG
            );
        }
        if (matches(tokens, "auth", "security", "permission", "token", "jwt", "安全", "权限", "鉴权")) {
            intent = "security_detail";
            planningReasons.add("security_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.SECURITY,
                    KnowledgeEvidenceType.API_CONTROLLER,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.CONFIG
            );
        }
        if (preferred.isEmpty() || matches(tokens, "核心流程", "流程", "业务", "架构", "模块", "overview", "概览")) {
            if (preferred.isEmpty()) {
                planningReasons.add("generic_overview_fallback");
            } else {
                planningReasons.add("overview_terms_detected");
            }
            addPreferred(preferred,
                    KnowledgeEvidenceType.MANUAL_DOC,
                    KnowledgeEvidenceType.GENERATED_DOC,
                    KnowledgeEvidenceType.CODE_MAP,
                    KnowledgeEvidenceType.API_CONTROLLER,
                    KnowledgeEvidenceType.SERVICE_CODE
            );
        }

        if (evidenceCoverageTarget(normalizedQuery, rawTokens, tokens)) {
            normalizedTermSet.add("evidence_coverage_api");
            if (implementationSourceTarget(normalizedQuery, rawTokens, tokens)) {
                intent = "implementation_detail";
                planningReasons.add("evidence_coverage_source_terms_detected");
                addPreferred(preferred,
                        KnowledgeEvidenceType.API_CONTROLLER,
                        KnowledgeEvidenceType.SERVICE_CODE,
                        KnowledgeEvidenceType.TEST
                );
            }
        }
        if (queryPlanTarget(normalizedQuery, rawTokens, tokens)) {
            normalizedTermSet.add("query_plan");
            intent = "implementation_detail";
            planningReasons.add("query_plan_source_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.TEST
            );
        }
        if (requiredEvidenceTarget(normalizedQuery, rawTokens, tokens)) {
            normalizedTermSet.add("required_evidence");
            intent = "implementation_detail";
            planningReasons.add("required_evidence_source_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.TEST
            );
        }
        if (databaseIntentMetaTarget(normalizedQuery, tokens)) {
            normalizedTermSet.add("database_detail_intent");
            intent = "implementation_detail";
            planningReasons.add("database_intent_meta_source_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.TEST
            );
        }
        if (knowledgeQueryPlannerTarget(normalizedQuery, tokens)) {
            normalizedTermSet.add("KnowledgeQueryPlanner");
            intent = "implementation_detail";
            planningReasons.add("knowledge_query_planner_source_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.TEST
            );
        }
        if (reviewContextTarget(normalizedQuery, rawTokens, tokens)) {
            if (normalizedQuery.toLowerCase(Locale.ROOT).contains("false positive")) {
                normalizedTermSet.add("false_positive_feedback");
            }
            if (matches(tokens, "memory", "feedback", "reviewmemorysignalservice")) {
                normalizedTermSet.add("review_memory");
            }
            intent = "review_context_detail";
            planningReasons.add("review_context_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.TEST
            );
        }
        if (knowledgeRagTarget(normalizedQuery, rawTokens, tokens)) {
            intent = "knowledge_rag_detail";
            planningReasons.add("knowledge_rag_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.TEST
            );
        }
        if (overviewTarget(normalizedQuery, rawTokens)) {
            intent = "project_overview";
            planningReasons.add("overview_terms_detected");
        }
        if (configurationTarget(normalizedQuery, rawTokens, tokens)) {
            intent = "configuration_detail";
            planningReasons.add("configuration_source_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.CONFIG,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.TEST
            );
        }
        if (reviewProductTarget(normalizedQuery, rawTokens, tokens)) {
            intent = "review_context_detail";
            planningReasons.add("review_context_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.TEST
            );
        }
        if (databaseSourceTarget(normalizedQuery, rawTokens, tokens)) {
            intent = "database_detail";
            planningReasons.add("database_source_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.SQL_SCHEMA,
                    KnowledgeEvidenceType.MAPPER,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.TEST
            );
        }
        if (testArtifactTarget(normalizedQuery, rawTokens, tokens)) {
            intent = "test_strategy";
            planningReasons.add("test_artifact_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.TEST,
                    KnowledgeEvidenceType.BENCHMARK,
                    KnowledgeEvidenceType.SERVICE_CODE
            );
        }
        if (implementationTarget(normalizedQuery, rawTokens, tokens)
                && implementationOverrideAllowed(intent, normalizedQuery, rawTokens, tokens)) {
            intent = "implementation_detail";
            planningReasons.add("implementation_source_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.CODE_MAP,
                    KnowledgeEvidenceType.API_CONTROLLER,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.TEST
            );
        }
        if (knowledgeRagTarget(normalizedQuery, rawTokens, tokens)
                && !implementationOverrideAllowed("knowledge_rag_detail", normalizedQuery, rawTokens, tokens)) {
            intent = "knowledge_rag_detail";
            planningReasons.add("knowledge_rag_terms_detected");
            addPreferred(preferred,
                    KnowledgeEvidenceType.SERVICE_CODE,
                    KnowledgeEvidenceType.TEST
            );
        }
        addRequiredEvidenceForIntent(intent, required);

        boolean hardSpecificEvidenceFallback = "performance_result".equals(intent);
        String noAnswerPolicy = hardSpecificEvidenceFallback
                ? "require_specific_evidence"
                : "require_retrieved_context";
        String fallbackStrategy = hardSpecificEvidenceFallback
                ? "require_specific_evidence_or_no_answer"
                : "retrieve_preferred_then_allow_partial_answer";
        return new KnowledgeQueryPlan(
                normalizedQuery,
                rewrittenQuery,
                normalizedTermSet.stream().toList(),
                intent,
                required.stream().toList(),
                preferred.stream().toList(),
                List.of(),
                sourceKinds(required.stream().toList()),
                sourceKinds(preferred.stream().toList()),
                forbiddenSourceKinds(intent),
                "evidence_grounded",
                noAnswerPolicy,
                fallbackStrategy,
                planningReasons.stream().toList()
        );
    }

    private String rewrite(String normalizedQuery, List<String> normalizedTerms) {
        if (normalizedTerms.isEmpty()) {
            return normalizedQuery;
        }
        LinkedHashSet<String> rewrittenTerms = new LinkedHashSet<>();
        rewrittenTerms.add(normalizedQuery);
        rewrittenTerms.addAll(normalizedTerms);
        return String.join(" ", rewrittenTerms);
    }

    private String normalizeWhitespace(String query) {
        return String.join(" ", (query == null ? "" : query.trim()).split("\\s+"));
    }

    private Set<String> tokenizeRaw(String value) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String separated = value == null ? "" : value
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([a-z\\d])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .toLowerCase(Locale.ROOT);
        for (String token : separated.split("\\s+")) {
            if (!token.isBlank()) {
                terms.add(token);
            }
        }
        return terms;
    }

    private boolean matches(Set<String> tokens, String... terms) {
        for (String token : tokens) {
            for (String term : terms) {
                if (token.contains(term)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addPreferred(LinkedHashSet<KnowledgeEvidenceType> preferred, KnowledgeEvidenceType... types) {
        for (KnowledgeEvidenceType type : types) {
            preferred.add(type);
        }
    }

    private void addRequired(LinkedHashSet<KnowledgeEvidenceType> required, KnowledgeEvidenceType... types) {
        for (KnowledgeEvidenceType type : types) {
            required.add(type);
        }
    }

    private void addRequiredEvidenceForIntent(String intent, LinkedHashSet<KnowledgeEvidenceType> required) {
        switch (intent) {
            case "database_detail" -> addRequired(required, KnowledgeEvidenceType.SQL_SCHEMA, KnowledgeEvidenceType.MAPPER);
            case "configuration_detail" -> addRequired(required, KnowledgeEvidenceType.CONFIG);
            case "observability_detail" -> addRequired(required, KnowledgeEvidenceType.OBSERVABILITY);
            case "test_strategy" -> addRequired(required, KnowledgeEvidenceType.TEST);
            case "implementation_detail", "review_context_detail", "knowledge_rag_detail" ->
                    addRequired(required, KnowledgeEvidenceType.SERVICE_CODE);
            case "cache_detail" -> addRequired(required, KnowledgeEvidenceType.CACHE);
            case "message_queue_detail" -> addRequired(required, KnowledgeEvidenceType.QUEUE);
            case "security_detail" -> addRequired(required, KnowledgeEvidenceType.SECURITY);
            default -> {
            }
        }
    }

    private boolean evidenceCoverageTarget(String normalizedQuery, Set<String> rawTokens, Set<String> tokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        return (matches(rawTokens, "evidence") && matches(rawTokens, "coverage"))
                || lower.contains("evidence-coverage")
                || (matchesText(lower, "\u8bc1\u636e") && matchesText(lower, "\u8986\u76d6"))
                || matches(tokens, "evidence_coverage_api");
    }

    private boolean implementationSourceTarget(String normalizedQuery, Set<String> rawTokens, Set<String> tokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        return matches(rawTokens,
                "api", "endpoint", "implemented", "implementation", "class", "classes", "model", "object",
                "report", "result", "return", "defined", "definition", "where", "call", "calls")
                || matches(tokens, "api", "endpoint", "implementation", "class", "method", "service", "model")
                || matchesText(lower,
                "\u5b9e\u73b0", "\u8c03\u7528", "\u7c7b", "\u8fd4\u56de", "\u5bf9\u8c61",
                "\u5b9a\u4e49", "\u54ea\u91cc");
    }

    private boolean queryPlanTarget(String normalizedQuery, Set<String> rawTokens, Set<String> tokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        return matches(rawTokens, "queryplan")
                || matchesText(lower, "queryplan", "query plan")
                || matches(tokens, "queryplan", "query_plan");
    }

    private boolean requiredEvidenceTarget(String normalizedQuery, Set<String> rawTokens, Set<String> tokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        return matches(rawTokens, "requiredevidence")
                || matchesText(lower, "required evidence", "required evidence types", "requiredevidence")
                || matches(tokens, "required_evidence");
    }

    private boolean databaseIntentMetaTarget(String normalizedQuery, Set<String> tokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        return matchesText(lower, "database_detail")
                || (matches(tokens, "database", "sql") && matchesText(lower, "intent", "\u5224\u65ad"));
    }

    private boolean knowledgeQueryPlannerTarget(String normalizedQuery, Set<String> tokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        return matchesText(lower, "knowledgequeryplanner", "knowledge query planner")
                || matches(tokens, "knowledgequeryplanner");
    }

    private boolean reviewContextTarget(String normalizedQuery, Set<String> rawTokens, Set<String> tokens) {
        return matches(rawTokens, "review")
                && (matches(rawTokens, "memory", "feedback", "filter", "false", "positive", "postprocessor")
                || matches(tokens, "reviewmemorysignalservice", "reviewreportpostprocessor"));
    }

    private boolean knowledgeRagTarget(String normalizedQuery, Set<String> rawTokens, Set<String> tokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        return matchesText(lower, "controlled deep scan")
                || (matches(rawTokens, "deep", "scan") && matches(tokens, "knowledge", "rag"));
    }

    private boolean overviewTarget(String normalizedQuery, Set<String> rawTokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        boolean overview = matches(rawTokens, "overview")
                || matchesText(lower,
                "\u6982\u89c8", "\u6982\u8981", "\u6574\u4f53", "\u6574\u4f53\u6d41\u7a0b",
                "\u5927\u6982", "\u80fd\u505a\u4ec0\u4e48", "\u662f\u4ec0\u4e48\u610f\u601d");
        if (!overview) {
            return false;
        }
        return !matches(rawTokens, "where", "implemented", "implementation", "source", "endpoint", "controller",
                "service", "repository", "test", "tests", "schema");
    }

    private boolean configurationTarget(String normalizedQuery, Set<String> rawTokens, Set<String> tokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        if (overviewTarget(normalizedQuery, rawTokens)) {
            return false;
        }
        if (matchesText(lower, "readonlycontextprovider", "read only context provider", "sandboxedreadonlycontextprovider")
                || (matches(rawTokens, "sandbox", "read", "source") && matches(rawTokens, "provider"))) {
            return false;
        }
        boolean llmSettings = matches(rawTokens, "llm", "deepseek")
                && matches(rawTokens, "settings", "provider", "model", "key", "timeout", "endpoint", "connected");
        boolean providerClient = matches(rawTokens, "provider", "client")
                && !matchesText(lower, "source evidence loop", "readonlycontextprovider", "read only context provider");
        boolean configTerms = matches(rawTokens, "config", "configuration", "settings", "timeout",
                "key", "application", "yml", "yaml", "properties");
        boolean sourceEvidenceProvider = matchesText(lower, "source evidence loop")
                || (matches(rawTokens, "source", "evidence") && matches(rawTokens, "provider"));
        if (sourceEvidenceProvider) {
            return false;
        }
        return llmSettings
                || providerClient
                || (configTerms && !matches(rawTokens, "docker", "compose", "deploy", "deployment"));
    }

    private boolean reviewProductTarget(String normalizedQuery, Set<String> rawTokens, Set<String> tokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        if (!matches(rawTokens, "review") && !matches(tokens, "review")) {
            return false;
        }
        if (overviewTarget(normalizedQuery, rawTokens)) {
            return false;
        }
        return matches(rawTokens, "memory", "feedback", "filter", "false", "positive", "postprocessor", "issue",
                "service", "entry", "entrypoint", "create", "created", "load")
                || matchesText(lower, "code review")
                || matches(tokens, "reviewmemorysignalservice", "reviewreportpostprocessor");
    }

    private boolean databaseSourceTarget(String normalizedQuery, Set<String> rawTokens, Set<String> tokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        if (databaseIntentMetaTarget(normalizedQuery, tokens)
                || queryPlanTarget(normalizedQuery, rawTokens, tokens)
                || requiredEvidenceTarget(normalizedQuery, rawTokens, tokens)
                || codeMapSchemaTarget(normalizedQuery, rawTokens)
                || reportContractTarget(normalizedQuery, rawTokens)) {
            return false;
        }
        if (matches(rawTokens, "code", "map") || matchesText(lower, "code map", "codemap")) {
            return false;
        }
        return matches(rawTokens, "sql", "schema", "table", "jdbc", "repository", "storage", "persistence",
                "migration", "database")
                || matchesText(lower, "repository storage", "database persistence", "schema.sql", "agent_event")
                || matches(tokens, "database", "database-schema");
    }

    private boolean testArtifactTarget(String normalizedQuery, Set<String> rawTokens, Set<String> tokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        boolean explicitTestArtifact = matchesText(lower, "tests", "test ", "contracttests")
                || rawTokens.stream().anyMatch(token -> token.endsWith("tests"));
        if (!explicitTestArtifact && !matches(tokens, "test", "testing")) {
            return false;
        }
        if (implementationTarget(normalizedQuery, rawTokens, tokens)
                && matches(rawTokens, "controller", "service", "model", "repository", "route", "api", "entry")) {
            return false;
        }
        return matches(rawTokens, "where", "implemented", "coverage", "strategy")
                || matchesText(lower, "\u6d4b\u8bd5", "\u8986\u76d6", "\u7b56\u7565");
    }

    private boolean implementationTarget(String normalizedQuery, Set<String> rawTokens, Set<String> tokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        if (testOnlyTarget(lower, rawTokens)) {
            return false;
        }
        boolean sourceOrLocation = matches(rawTokens, "where", "implemented", "implementation", "source", "locate",
                "defined", "definition", "controller", "service", "endpoint", "route", "model", "type", "class",
                "classes", "logic", "connect", "entry", "entrypoint", "runner", "harness", "contract", "fields",
                "json")
                || matchesText(lower,
                "\u6e90\u7801", "\u5b9e\u73b0", "\u5728\u54ea", "\u54ea\u91cc", "\u5b9a\u4e49", "\u8c03\u7528",
                "\u5165\u53e3", "\u7c7b", "\u8fd4\u56de", "\u5bf9\u8c61");
        boolean namedProductSurface = matchesText(lower,
                "context generation", "projectgraph", "project graph", "knowledge rag", "answer guard",
                "controlleddeepscanservice", "sandboxedreadonlycontextprovider", "readonlycontextprovider",
                "projectprofile", "benchmark report", "evidence coverage", "source evidence loop",
                "projectcontextcontroller", "knowledgequeryplantraceformatter",
                "\u4e0a\u4e0b\u6587\u751f\u6210", "\u5177\u4f53\u600e\u4e48\u505a");
        return sourceOrLocation || namedProductSurface;
    }

    private boolean implementationOverrideAllowed(
            String currentIntent,
            String normalizedQuery,
            Set<String> rawTokens,
            Set<String> tokens
    ) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        if ("review_context_detail".equals(currentIntent)
                || "cache_detail".equals(currentIntent)
                || "message_queue_detail".equals(currentIntent)
                || "security_detail".equals(currentIntent)) {
            return false;
        }
        if ("database_detail".equals(currentIntent)) {
            return queryPlanTarget(normalizedQuery, rawTokens, tokens)
                    || requiredEvidenceTarget(normalizedQuery, rawTokens, tokens)
                    || databaseIntentMetaTarget(normalizedQuery, tokens)
                    || codeMapSchemaTarget(normalizedQuery, rawTokens)
                    || reportContractTarget(normalizedQuery, rawTokens);
        }
        if ("performance_result".equals(currentIntent)) {
            return reportContractTarget(normalizedQuery, rawTokens);
        }
        if ("test_strategy".equals(currentIntent)) {
            if (matches(rawTokens, "test", "tests")
                    && !matches(rawTokens, "controller", "model", "route", "api", "entry", "entrypoint", "graph")) {
                return false;
            }
            return matches(rawTokens, "controller", "model", "route", "api", "entry", "entrypoint", "graph")
                    || evidenceCoverageTarget(normalizedQuery, rawTokens, tokens);
        }
        if ("configuration_detail".equals(currentIntent)) {
            return matchesText(lower, "projectprofile", "answer guard", "evidence-grounded answer guard")
                    || (matches(rawTokens, "profile") && matches(rawTokens, "source", "freshness", "status"))
                    || answerGuardTarget(normalizedQuery, rawTokens)
                    || sourceEvidenceProviderTarget(normalizedQuery, rawTokens);
        }
        if ("knowledge_rag_detail".equals(currentIntent)) {
            return matchesText(lower, "controlleddeepscanservice", "sandboxedreadonlycontextprovider")
                    || matches(rawTokens, "where", "tests", "test", "core");
        }
        return true;
    }

    private boolean answerGuardTarget(String normalizedQuery, Set<String> rawTokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        return matchesText(lower, "answer guard", "evidence-grounded answer guard")
                || (matches(rawTokens, "supported", "partial", "insufficient") && matches(rawTokens, "knowledge", "rag"));
    }

    private boolean sourceEvidenceProviderTarget(String normalizedQuery, Set<String> rawTokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        return matchesText(lower, "source evidence loop")
                || (matches(rawTokens, "source", "evidence") && matches(rawTokens, "provider", "sandbox", "read"));
    }

    private boolean codeMapSchemaTarget(String normalizedQuery, Set<String> rawTokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        return matchesText(lower, "code map", "codemap")
                || (matches(rawTokens, "code", "map") && matches(rawTokens, "schema"));
    }

    private boolean reportContractTarget(String normalizedQuery, Set<String> rawTokens) {
        String lower = normalizedQuery.toLowerCase(Locale.ROOT);
        return matches(rawTokens, "report", "contract", "json", "fields")
                || matchesText(lower, "report contract", "json field", "json fields");
    }

    private boolean testOnlyTarget(String lower, Set<String> rawTokens) {
        boolean testMentioned = matchesText(lower, "tests", "test ", "contracttests")
                || rawTokens.stream().anyMatch(token -> token.endsWith("tests"));
        if (!testMentioned) {
            return false;
        }
        return !matches(rawTokens, "controller", "service", "model", "repository", "route", "api", "entry",
                "entrypoint", "graph", "context", "answer", "guard", "provider", "client");
    }

    private boolean matchesText(String value, String... terms) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (normalized.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private List<String> sourceKinds(List<KnowledgeEvidenceType> evidenceTypes) {
        return evidenceTypes.stream()
                .map(type -> type.sourceKind().value())
                .distinct()
                .toList();
    }

    private List<String> forbiddenSourceKinds(String intent) {
        return switch (intent) {
            case "database_detail", "observability_detail", "performance_result", "implementation_detail",
                    "cache_detail", "message_queue_detail", "security_detail" -> List.of("documentation");
            default -> List.of();
        };
    }
}
