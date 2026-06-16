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
        List<String> normalizedTerms = queryTermNormalizer.normalize(query);
        String rewrittenQuery = rewrite(normalizedQuery, normalizedTerms);

        Set<String> tokens = new LinkedHashSet<>();
        tokens.add(normalizedQuery.toLowerCase(Locale.ROOT));
        normalizedTerms.stream()
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

        String noAnswerPolicy = required.isEmpty()
                ? "require_retrieved_context"
                : "require_specific_evidence";
        String fallbackStrategy = required.isEmpty()
                ? "retrieve_preferred_then_allow_partial_answer"
                : "require_specific_evidence_or_no_answer";
        return new KnowledgeQueryPlan(
                normalizedQuery,
                rewrittenQuery,
                normalizedTerms,
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
        return String.join(" ", query.trim().split("\\s+"));
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
