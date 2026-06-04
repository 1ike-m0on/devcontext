package com.devcontext.application.knowledge;

import com.devcontext.domain.knowledge.KnowledgeChunkView;
import com.devcontext.domain.knowledge.KnowledgeEvidenceType;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeEvidenceClassifier {

    public List<KnowledgeEvidenceType> classify(KnowledgeChunkView view) {
        return classify(
                view.document().filePath(),
                view.document().title(),
                view.chunk().headingPath(),
                view.chunk().content()
        );
    }

    public List<KnowledgeEvidenceType> classify(String filePath, String title, String headingPath, String content) {
        String path = lower(filePath);
        String text = lower(String.join("\n",
                safe(filePath),
                safe(title),
                safe(headingPath),
                safe(content)
        ));
        EnumSet<KnowledgeEvidenceType> types = EnumSet.noneOf(KnowledgeEvidenceType.class);

        classifyByPath(path, types);
        classifyByContent(text, types);

        if (types.isEmpty()) {
            types.add(KnowledgeEvidenceType.GENERATED_DOC);
        }
        return types.stream().toList();
    }

    private void classifyByPath(String path, EnumSet<KnowledgeEvidenceType> types) {
        if (path.startsWith(".ai/manual/")) {
            types.add(KnowledgeEvidenceType.MANUAL_DOC);
        }
        if (path.startsWith(".ai/generated/") || path.equals(".ai/ai_readme.md") || path.equals("agents.md")) {
            types.add(KnowledgeEvidenceType.GENERATED_DOC);
        }
        if (path.equals(".ai/code-map.json") || path.endsWith("/code-map.json")) {
            types.add(KnowledgeEvidenceType.CODE_MAP);
        }
        if (path.endsWith(".sql") || path.contains("/migration/") || path.contains("/migrations/")
                || path.contains("/schema/") || path.contains("/db/")) {
            types.add(KnowledgeEvidenceType.SQL_SCHEMA);
        }
        if (path.endsWith("mapper.xml") || path.contains("/mapper/") || path.contains("/mybatis/")) {
            types.add(KnowledgeEvidenceType.MAPPER);
        }
        if (path.endsWith("application.yml") || path.endsWith("application.yaml")
                || path.endsWith("application.properties") || path.contains("/config/")
                || path.contains("/resources/")) {
            types.add(KnowledgeEvidenceType.CONFIG);
        }
        if (isDeploymentPath(path)) {
            types.add(KnowledgeEvidenceType.DEPLOYMENT);
        }
        if (isObservabilityPath(path)) {
            types.add(KnowledgeEvidenceType.OBSERVABILITY);
        }
        if (path.contains("/test/") || path.contains("/tests/") || path.contains("/fixtures/")
                || path.endsWith("test.java") || path.endsWith("tests.java")) {
            types.add(KnowledgeEvidenceType.TEST);
        }
        if (path.contains("benchmark") || path.contains("jmeter") || path.contains("k6") || path.contains("load-test")) {
            types.add(KnowledgeEvidenceType.BENCHMARK);
        }
        if (path.contains(".github/workflows") || path.contains("/ci/") || path.contains("github-actions")) {
            types.add(KnowledgeEvidenceType.CI);
        }
        if (path.contains("controller") || path.contains("/api/") || path.contains("/web/")) {
            types.add(KnowledgeEvidenceType.API_CONTROLLER);
        }
        if (path.contains("service") || path.contains("handler") || path.contains("usecase") || path.contains("application/")) {
            types.add(KnowledgeEvidenceType.SERVICE_CODE);
        }
        if (path.contains("consumer") || path.contains("producer") || path.contains("rocketmq")
                || path.contains("kafka") || path.contains("rabbitmq") || path.contains("/mq/")) {
            types.add(KnowledgeEvidenceType.QUEUE);
        }
        if (path.contains("redis") || path.contains("cache") || path.endsWith(".lua")) {
            types.add(KnowledgeEvidenceType.CACHE);
        }
        if (path.contains("security") || path.contains("auth") || path.contains("permission")
                || path.contains("token") || path.contains("privacy")) {
            types.add(KnowledgeEvidenceType.SECURITY);
        }
    }

    private void classifyByContent(String text, EnumSet<KnowledgeEvidenceType> types) {
        if (containsAny(text, "create index", "primary key", "foreign key", "create table", "alter table")) {
            types.add(KnowledgeEvidenceType.SQL_SCHEMA);
        }
        if (containsAny(text, "mybatis", "@select", "@insert", "@update", "@delete", "<select", "<insert", "<update")) {
            types.add(KnowledgeEvidenceType.MAPPER);
        }
        if (containsAny(text, "management.endpoints", "spring.datasource", "server.port", "application.yml")) {
            types.add(KnowledgeEvidenceType.CONFIG);
        }
        if (containsAny(text, "docker compose", "docker-compose", "kubernetes", "deployment.yaml", "helm")) {
            types.add(KnowledgeEvidenceType.DEPLOYMENT);
        }
        if (containsAny(text, "prometheus", "grafana", "meterregistry", "micrometer", "actuator", "metrics")) {
            types.add(KnowledgeEvidenceType.OBSERVABILITY);
        }
        if (containsAny(text, "@test", "junit", "mockmvc", "assertthat", "fixture")) {
            types.add(KnowledgeEvidenceType.TEST);
        }
        if (containsAny(text, "benchmark", "qps", "p95", "p99", "jmeter", "k6", "load test")) {
            types.add(KnowledgeEvidenceType.BENCHMARK);
        }
        if (containsAny(text, "@requestmapping", "@getmapping", "@postmapping", "restcontroller", "controller")) {
            types.add(KnowledgeEvidenceType.API_CONTROLLER);
        }
        if (containsAny(text, "@service", "service", "handler", "commandhandler", "usecase")) {
            types.add(KnowledgeEvidenceType.SERVICE_CODE);
        }
        if (containsAny(text, "rocketmq", "kafka", "rabbitmq", "consumer", "producer", "message queue")) {
            types.add(KnowledgeEvidenceType.QUEUE);
        }
        if (containsAny(text, "redis", "lua", "cache", "caffeine", "invalidation")) {
            types.add(KnowledgeEvidenceType.CACHE);
        }
        if (containsAny(text, "authorization", "authentication", "permission", "token", "jwt", "privacy")) {
            types.add(KnowledgeEvidenceType.SECURITY);
        }
    }

    private boolean isDeploymentPath(String path) {
        return path.equals("compose.yml")
                || path.equals("compose.yaml")
                || path.equals("docker-compose.yml")
                || path.equals("docker-compose.yaml")
                || path.endsWith("/compose.yml")
                || path.endsWith("/compose.yaml")
                || path.endsWith("/docker-compose.yml")
                || path.endsWith("/docker-compose.yaml")
                || path.contains("dockerfile")
                || path.startsWith("deploy/")
                || path.startsWith("k8s/")
                || path.contains("/kubernetes/")
                || path.contains("deployment.md");
    }

    private boolean isObservabilityPath(String path) {
        return path.contains("monitoring")
                || path.contains("prometheus")
                || path.contains("grafana")
                || path.contains("metrics")
                || path.contains("micrometer")
                || path.contains("actuator")
                || path.contains("observability");
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String lower(String value) {
        return safe(value).toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
