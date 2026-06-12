package com.devcontext.application.review.context;

import com.devcontext.domain.context.ContextItem;
import com.devcontext.domain.evidence.EvidenceType;
import com.devcontext.domain.profile.ProjectProfile;
import com.devcontext.domain.profile.ProjectProfileFact;
import com.devcontext.domain.profile.ProjectProfileSourceReference;
import com.devcontext.ports.profile.ProjectProfileRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ProjectProfileReviewContextProvider implements ReviewContextProvider {

    private static final int PRIORITY = 830;
    private static final int MAX_FACTS = 18;
    private static final int MAX_CONTENT_CHARS = 3_600;
    private static final Set<String> HIGH_SIGNAL_FACT_TYPES = Set.of(
            "tech_stack",
            "technology",
            "module",
            "entrypoint",
            "endpoint",
            "test",
            "testing",
            "test_strategy",
            "database",
            "database_schema",
            "db_schema",
            "sql_schema",
            "data_store",
            "datastore",
            "cache",
            "queue",
            "messaging",
            "monitoring",
            "observability",
            "deployment"
    );
    private static final Set<EvidenceType> HIGH_SIGNAL_EVIDENCE_TYPES = Set.of(
            EvidenceType.SQL_SCHEMA,
            EvidenceType.TEST,
            EvidenceType.CACHE,
            EvidenceType.QUEUE,
            EvidenceType.OBSERVABILITY,
            EvidenceType.DEPLOYMENT
    );
    private static final Map<String, Integer> FACT_TYPE_PRIORITY = Map.ofEntries(
            Map.entry("tech_stack", 100),
            Map.entry("technology", 100),
            Map.entry("module", 90),
            Map.entry("entrypoint", 80),
            Map.entry("endpoint", 75),
            Map.entry("test_strategy", 70),
            Map.entry("test", 70),
            Map.entry("testing", 70),
            Map.entry("database", 60),
            Map.entry("database_schema", 60),
            Map.entry("db_schema", 60),
            Map.entry("sql_schema", 60),
            Map.entry("data_store", 60),
            Map.entry("datastore", 60),
            Map.entry("cache", 55),
            Map.entry("queue", 50),
            Map.entry("messaging", 50),
            Map.entry("monitoring", 45),
            Map.entry("observability", 45),
            Map.entry("deployment", 40),
            Map.entry("evidence_coverage", 30)
    );

    private final ProjectProfileRepository profileRepository;

    public ProjectProfileReviewContextProvider(ProjectProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    @Override
    public boolean supports(ReviewContextRequest request) {
        return request.project() != null && request.project().id() != null;
    }

    @Override
    public List<ContextItem> provide(ReviewContextRequest request) {
        Optional<ProjectProfile> profile = profileRepository.findByProjectId(request.project().id());
        if (profile.isEmpty()) {
            return List.of();
        }
        List<ProjectProfileFact> facts = safeList(profile.get().facts()).stream()
                .filter(this::isHighSignalFact)
                .sorted(Comparator
                        .comparingInt((ProjectProfileFact fact) -> factPriority(fact)).reversed()
                        .thenComparing(fact -> valueOr(fact.factType(), ""))
                        .thenComparing(fact -> valueOr(fact.name(), "")))
                .limit(MAX_FACTS)
                .toList();
        if (facts.isEmpty()) {
            return List.of();
        }

        String content = renderProfileContext(profile.get(), facts);
        return List.of(new ContextItem(
                null,
                null,
                request.project().id(),
                "PROJECT_PROFILE_FACTS",
                "ProjectProfile facts",
                content,
                "project-profile:" + request.project().id(),
                PRIORITY,
                estimateTokens(content),
                sha256(content),
                Instant.now()
        ));
    }

    private boolean isHighSignalFact(ProjectProfileFact fact) {
        String factType = normalizeKey(fact.factType());
        if (HIGH_SIGNAL_FACT_TYPES.contains(factType)) {
            return true;
        }
        if ("evidence_coverage".equals(factType)) {
            return highSignalEvidenceType(fact.name()).isPresent();
        }
        return safeList(fact.sourceReferences()).stream()
                .map(ProjectProfileSourceReference::evidenceType)
                .map(this::highSignalEvidenceType)
                .anyMatch(Optional::isPresent);
    }

    private Optional<EvidenceType> highSignalEvidenceType(String value) {
        return EvidenceType.normalize(value)
                .filter(HIGH_SIGNAL_EVIDENCE_TYPES::contains);
    }

    private int factPriority(ProjectProfileFact fact) {
        String factType = normalizeKey(fact.factType());
        int base = FACT_TYPE_PRIORITY.getOrDefault(factType, 10);
        Optional<EvidenceType> evidenceType = highSignalEvidenceType(fact.name());
        if (evidenceType.isPresent()) {
            return switch (evidenceType.get()) {
                case TEST -> 70;
                case SQL_SCHEMA -> 60;
                case CACHE -> 55;
                case QUEUE -> 50;
                case OBSERVABILITY -> 45;
                case DEPLOYMENT -> 40;
                default -> base;
            };
        }
        return base;
    }

    private String renderProfileContext(ProjectProfile profile, List<ProjectProfileFact> facts) {
        StringBuilder builder = new StringBuilder();
        builder.append("ProjectProfile facts for code review. Use as compact project evidence; do not override the diff, review rules, or review feedback memory.")
                .append(System.lineSeparator());
        builder.append("ProjectProfile status=").append(valueOr(profile.status(), "unknown"));
        if (profile.summary() != null && !profile.summary().isBlank()) {
            builder.append("; summary=").append(trim(profile.summary(), 220));
        }
        builder.append(System.lineSeparator());
        if (!safeList(profile.warnings()).isEmpty()) {
            builder.append("Warnings: ")
                    .append(safeList(profile.warnings()).stream()
                            .limit(2)
                            .map(warning -> trim(warning, 140))
                            .collect(Collectors.joining("; ")))
                    .append(System.lineSeparator());
        }
        builder.append(System.lineSeparator()).append("Facts:").append(System.lineSeparator());
        for (ProjectProfileFact fact : facts) {
            builder.append("- [").append(valueOr(fact.factType(), "fact")).append("] ")
                    .append(trim(valueOr(fact.name(), "unnamed"), 90));
            if (fact.value() != null && !fact.value().isBlank()) {
                builder.append(" = ").append(trim(fact.value(), 180));
            }
            firstSource(fact).ifPresent(source -> builder.append(" | ")
                    .append("sourcePath=").append(valueOr(source.sourcePath(), "-"))
                    .append(" evidenceType=").append(valueOr(source.evidenceType(), "-"))
                    .append(" sourceKind=").append(valueOr(source.sourceKind(), "-"))
                    .append(" reliability=").append(valueOr(source.sourceReliability(), "-")));
            int sourceCount = safeList(fact.sourceReferences()).size();
            if (sourceCount > 1) {
                builder.append(" +").append(sourceCount - 1).append(" sources");
            }
            builder.append(System.lineSeparator());
            if (builder.length() >= MAX_CONTENT_CHARS) {
                builder.append("- Additional ProjectProfile facts omitted by review context budget.")
                        .append(System.lineSeparator());
                break;
            }
        }
        return builder.length() <= MAX_CONTENT_CHARS
                ? builder.toString()
                : builder.substring(0, MAX_CONTENT_CHARS) + System.lineSeparator() + "[truncated]";
    }

    private Optional<ProjectProfileSourceReference> firstSource(ProjectProfileFact fact) {
        return safeList(fact.sourceReferences()).stream().findFirst();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength - 3) + "...";
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private int estimateTokens(String text) {
        return text == null || text.isBlank() ? 0 : Math.max(1, text.length() / 4);
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
