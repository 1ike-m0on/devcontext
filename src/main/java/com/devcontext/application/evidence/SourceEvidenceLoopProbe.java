package com.devcontext.application.evidence;

import com.devcontext.application.context.ReadOnlyContextProvider;
import com.devcontext.application.context.SandboxedReadOnlyContextProvider;
import com.devcontext.domain.context.ReadOnlyContextBudget;
import com.devcontext.domain.context.ReadOnlyContextFileReadRequest;
import com.devcontext.domain.context.ReadOnlyContextFileSearchRequest;
import com.devcontext.domain.context.ReadOnlyContextReadResult;
import com.devcontext.domain.context.ReadOnlyContextSearchMatch;
import com.devcontext.domain.context.ReadOnlyContextSearchResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SourceEvidenceLoopProbe {

    private static final String COVERED = "covered";
    private static final String MISSING = "missing";
    private static final int DEFAULT_MAX_ITERATIONS = 3;
    private static final int DEFAULT_MAX_EVIDENCE_TOKENS = 12_000;
    private static final int MAX_SEARCH_FILES = 2_000;
    private static final int MAX_DISCOVERY_CANDIDATES_PER_SPEC = 4;
    private static final int MAX_DISCOVERY_CANDIDATES_PER_GROUP = 8;
    private static final int MAX_PROVIDER_READ_CHARS = 220_000;
    private static final int MAX_PROVIDER_READ_LINES = 5_000;
    private static final int MAX_BLOCKED_LEGACY_SOURCES = 80;
    private static final int MAX_FRAGMENT_LINES = 220;
    private static final Pattern METHOD_LIKE_PATTERN = Pattern.compile(
            "^\\s*(?:public|private|protected|static|final|synchronized|abstract|default|void|[A-Za-z_$][A-Za-z0-9_$<>\\[\\],.?]+)\\b.*\\([^;]*\\)\\s*(?:throws\\s+[^{}]+)?\\{?\\s*$"
    );
    private static final Pattern QUERY_TOKEN_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_/-]*|[\\p{IsHan}]+|[0-9]+");
    private static final Pattern CAMEL_TOKEN_PATTERN = Pattern.compile("\\b[A-Z][A-Za-z0-9]+\\b");
    private static final Pattern CAMEL_PART_PATTERN = Pattern.compile("[A-Z]?[a-z]+|[A-Z]+(?![a-z])|[0-9]+");
    private static final Pattern FILE_OR_PATH_FRAGMENT_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+\\b|\\b[A-Za-z0-9_.-]+\\.(?:java|sql|xml|yml|yaml|properties|json|md|txt)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "a",
            "an",
            "and",
            "are",
            "as",
            "be",
            "by",
            "for",
            "from",
            "how",
            "in",
            "is",
            "it",
            "of",
            "on",
            "or",
            "the",
            "to",
            "with",
            "where"
    );
    private static final Set<String> BLOCKED_DIRECTORY_PREFIXES = Set.of(
            ".git/",
            ".ai/generated/",
            ".ai/manual/",
            ".ai/reviews/",
            "build/",
            "data/",
            "dist/",
            ".idea/",
            "docs/",
            "logs/",
            "node_modules/",
            "out/",
            "project_ai_docs/",
            "target/"
    );
    private static final Set<String> BLOCKED_EXACT_FILES = Set.of(
            ".ai/AI_README.md",
            "README.md",
            "README.en.md",
            "README.ja-JP.md"
    );
    private static final Set<String> SEARCHABLE_EXTENSIONS = Set.of(
            ".java",
            ".sql",
            ".xml",
            ".yml",
            ".yaml",
            ".properties",
            ".json",
            ".py",
            ".ts",
            ".tsx",
            ".js",
            ".jsx",
            ".go",
            ".rs",
            ".c",
            ".cc",
            ".cpp",
            ".h",
            ".hpp",
            ".md",
            ".txt",
            ".env",
            ".example"
    );

    private final ReadOnlyContextProvider readOnlyContextProvider;
    private final ConcurrentMap<Path, DiscoveryCorpus> discoveryCorpusCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Path, List<String>> blockedLegacySourcesCache = new ConcurrentHashMap<>();

    public SourceEvidenceLoopProbe() {
        this(new SandboxedReadOnlyContextProvider(trace -> {
        }));
    }

    @Autowired
    public SourceEvidenceLoopProbe(ReadOnlyContextProvider readOnlyContextProvider) {
        this.readOnlyContextProvider = readOnlyContextProvider;
    }

    public ProbeResult run(ProbeRequest request) {
        ProbeRequest safeRequest = request == null
                ? new ProbeRequest(null, "", DEFAULT_MAX_ITERATIONS, DEFAULT_MAX_EVIDENCE_TOKENS)
                : request;
        Path root = resolveProjectRoot(safeRequest.projectRoot());
        QueryFocus queryFocus = extractQueryFocus(safeRequest.question());
        EvidenceContract contract = classify(queryFocus, safeRequest.intentOverride());
        int maxIterations = safeRequest.maxIterations() <= 0 ? DEFAULT_MAX_ITERATIONS : safeRequest.maxIterations();
        int maxEvidenceChars = Math.max(4_000, (safeRequest.maxEvidenceTokens() <= 0
                ? DEFAULT_MAX_EVIDENCE_TOKENS
                : safeRequest.maxEvidenceTokens()) * 4);

        List<String> blockedLegacySources = blockedLegacySources(root);
        DiscoveryCorpus corpus = buildDiscoveryCorpus(root);
        List<CandidatePlan> candidatePlans = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        List<EvidenceFragment> evidencePack = new ArrayList<>();
        List<FileRead> filesRead = new ArrayList<>();
        List<Iteration> iterations = new ArrayList<>();
        LinkedHashMap<String, String> groupStatus = initialGroupStatus(contract);
        LinkedHashSet<String> seenCandidates = new LinkedHashSet<>();
        LinkedHashSet<String> seenReads = new LinkedHashSet<>();

        List<String> groupsToFind = contract.requiredEvidenceGroups();
        for (int iterationNumber = 1; iterationNumber <= maxIterations; iterationNumber++) {
            List<CandidatePlan> iterationPlans = iterationNumber == 1
                    ? seedCandidates(root, corpus, contract, queryFocus, seenCandidates)
                    : expansionCandidates(root, corpus, contract, queryFocus, groupsToFind, evidencePack, seenCandidates);
            candidatePlans.addAll(iterationPlans);
            candidates.addAll(iterationPlans.stream().map(CandidatePlan::candidate).toList());

            int charsUsed = evidencePack.stream().mapToInt(fragment -> fragment.content().length()).sum();
            for (CandidatePlan plan : interleaveByEvidenceGroup(iterationPlans, contract.requiredEvidenceGroups())) {
                if (charsUsed >= maxEvidenceChars) {
                    break;
                }
                Optional<EvidenceFragment> fragment = readEvidence(root, plan, maxEvidenceChars - charsUsed);
                if (fragment.isEmpty()) {
                    continue;
                }
                EvidenceFragment evidence = fragment.get();
                String readKey = evidence.path() + ":" + evidence.startLine() + ":" + evidence.endLine() + ":" + evidence.evidenceGroup();
                if (!seenReads.add(readKey)) {
                    continue;
                }
                evidencePack.add(evidence);
                filesRead.add(new FileRead(evidence.path(), evidence.startLine(), evidence.endLine(), evidence.evidenceGroup()));
                charsUsed += evidence.content().length();
            }

            groupStatus = evaluateSufficiency(contract, queryFocus, evidencePack);
            if ("database_detail".equals(contract.intent())) {
                groupStatus = requireRepositoryLinkedModel(corpus, evidencePack, groupStatus);
            }
            iterations.add(new Iteration(
                    iterationNumber,
                    iterationNumber == 1 ? "seed_candidates" : "expand_missing_evidence",
                    iterationPlans.stream().map(CandidatePlan::candidate).toList(),
                    groupStatus
            ));
            groupsToFind = missingGroups(groupStatus);
            if (groupsToFind.isEmpty()) {
                break;
            }
        }

        Sufficiency sufficiency = new Sufficiency(
                missingGroups(groupStatus).isEmpty() ? "supported" : "missing",
                groupStatus,
                missingGroups(groupStatus)
        );
        List<EvidenceFragment> primaryEvidencePack = compactPrimaryEvidencePack(contract, queryFocus, evidencePack);
        return new ProbeResult(
                contract.intent(),
                queryFocus,
                contract.requiredEvidenceGroups(),
                iterations,
                candidates,
                filesRead,
                primaryEvidencePack,
                sufficiency,
                blockedLegacySources
        );
    }

    private EvidenceContract classify(QueryFocus queryFocus) {
        String normalized = normalize(queryFocus.originalQuestion());
        Set<String> intents = Set.copyOf(queryFocus.domainIntents());
        if (containsAny(normalized, "sql", "database", "persistence", "jdbc", "schema", "数据库", "持久", "表结构")
                || intents.contains("sql")
                || intents.contains("schema")
                || intents.contains("repository")
                || intents.contains("database")
                || intents.contains("persistence")) {
            return databaseContract();
        }
        if (containsAny(normalized, "false positive", "false_positive", "code review", "review", "反馈", "后续")
                || intents.contains("review")
                || intents.contains("feedback")) {
            return reviewContextContract();
        }
        if (containsAny(normalized, "llm", "provider", "model", "key", "timeout", "settings", "配置", "测试")
                || intents.contains("llm")
                || intents.contains("settings")
                || intents.contains("provider")
                || intents.contains("timeout")) {
            return configurationContract();
        }
        return implementationContract();
    }

    private EvidenceContract classify(QueryFocus queryFocus, String intentOverride) {
        return switch (normalize(intentOverride)) {
            case "implementation_detail" -> implementationContract();
            case "database_detail" -> databaseContract();
            case "configuration_detail" -> configurationContract();
            case "review_context_detail" -> reviewContextContract();
            default -> classify(queryFocus);
        };
    }

    private QueryFocus extractQueryFocus(String question) {
        String original = question == null ? "" : question.trim();
        String normalizedQuestion = normalize(original);
        LinkedHashSet<String> rawTokens = new LinkedHashSet<>();
        LinkedHashSet<String> chineseKeywords = new LinkedHashSet<>();
        LinkedHashSet<String> englishKeywords = new LinkedHashSet<>();
        LinkedHashSet<String> camelCaseNames = new LinkedHashSet<>();
        LinkedHashSet<String> simpleNames = new LinkedHashSet<>();
        LinkedHashSet<String> fileOrPathFragments = new LinkedHashSet<>();
        LinkedHashSet<String> domainIntents = new LinkedHashSet<>();

        Matcher tokenMatcher = QUERY_TOKEN_PATTERN.matcher(original);
        while (tokenMatcher.find()) {
            rawTokens.add(tokenMatcher.group());
        }

        Matcher pathMatcher = FILE_OR_PATH_FRAGMENT_PATTERN.matcher(original);
        while (pathMatcher.find()) {
            fileOrPathFragments.add(pathMatcher.group());
        }

        Matcher camelMatcher = CAMEL_TOKEN_PATTERN.matcher(original);
        while (camelMatcher.find()) {
            String token = camelMatcher.group();
            camelCaseNames.add(token);
            splitCamelParts(token).forEach(part -> {
                if (isUsefulEnglishKeyword(part)) {
                    simpleNames.add(part);
                }
            });
        }

        for (String rawToken : rawTokens) {
            if (containsHan(rawToken)) {
                chineseKeywords.add(rawToken);
                continue;
            }
            for (String part : rawToken.split("[^A-Za-z0-9]+")) {
                String keyword = normalize(part);
                if (isUsefulEnglishKeyword(keyword)) {
                    englishKeywords.add(keyword);
                    simpleNames.add(keyword);
                }
            }
        }

        addChineseKeywordIfPresent(original, chineseKeywords, "上下文");
        addChineseKeywordIfPresent(original, chineseKeywords, "生成");
        addChineseKeywordIfPresent(original, chineseKeywords, "实现");
        addChineseKeywordIfPresent(original, chineseKeywords, "核心源码");
        addChineseKeywordIfPresent(original, chineseKeywords, "数据库");
        addChineseKeywordIfPresent(original, chineseKeywords, "持久");
        addChineseKeywordIfPresent(original, chineseKeywords, "反馈");
        addChineseKeywordIfPresent(original, chineseKeywords, "后续");
        addChineseKeywordIfPresent(original, chineseKeywords, "配置");
        addChineseKeywordIfPresent(original, chineseKeywords, "测试");

        expandStructuredFocus(original, englishKeywords, camelCaseNames, simpleNames, fileOrPathFragments, domainIntents);
        addGeneratedCamelForms(camelCaseNames, englishKeywords.stream().toList());
        inferDomainIntents(normalizedQuestion, chineseKeywords, englishKeywords, fileOrPathFragments, domainIntents);
        if (domainIntents.contains("sql") || domainIntents.contains("schema")) {
            fileOrPathFragments.add(".sql");
            fileOrPathFragments.add("schema");
        }
        if (domainIntents.contains("test")) {
            fileOrPathFragments.add("test");
        }

        return new QueryFocus(
                original,
                rawTokens.stream().toList(),
                chineseKeywords.stream().toList(),
                englishKeywords.stream().toList(),
                compactLowercaseForOutput(original),
                camelCaseNames.stream().toList(),
                simpleNames.stream().toList(),
                fileOrPathFragments.stream().toList(),
                domainIntents.stream().toList()
        );
    }

    private void addChineseKeywordIfPresent(String question, Set<String> keywords, String keyword) {
        if (question.contains(keyword)) {
            keywords.add(keyword);
        }
    }

    private boolean containsHan(String value) {
        return value != null && value.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private boolean isUsefulEnglishKeyword(String token) {
        String normalized = normalize(token);
        return normalized.length() >= 2 && !QUERY_STOP_WORDS.contains(normalized);
    }

    private List<String> splitCamelParts(String token) {
        List<String> parts = new ArrayList<>();
        Matcher matcher = CAMEL_PART_PATTERN.matcher(token);
        while (matcher.find()) {
            String part = normalize(matcher.group());
            if (isUsefulEnglishKeyword(part)) {
                parts.add(part);
            }
        }
        return parts;
    }

    private void expandStructuredFocus(
            String original,
            Set<String> englishKeywords,
            Set<String> camelCaseNames,
            Set<String> simpleNames,
            Set<String> fileOrPathFragments,
            Set<String> domainIntents
    ) {
        String normalizedQuestion = normalize(original);
        boolean evidenceCoverage = (englishKeywords.contains("evidence") && englishKeywords.contains("coverage"))
                || normalizedQuestion.contains("evidence-coverage")
                || normalizedQuestion.contains("evidence coverage")
                || normalizedQuestion.contains("coverage endpoint")
                || normalizedQuestion.contains("coverage api")
                || original.contains("证据覆盖");
        if (original.contains("项目")) {
            englishKeywords.add("project");
            simpleNames.add("project");
        }
        if (original.contains("上下文")) {
            englishKeywords.add("context");
            simpleNames.add("context");
            camelCaseNames.add("Context");
            camelCaseNames.add("ProjectContext");
            domainIntents.add("service");
        }
        if (original.contains("生成")) {
            englishKeywords.add("generate");
            englishKeywords.add("generation");
            simpleNames.add("generate");
            simpleNames.add("generation");
            camelCaseNames.add("ContextGeneration");
            camelCaseNames.add("ContextGenerationResult");
            camelCaseNames.add("ContextResult");
            camelCaseNames.add("ContextService");
            fileOrPathFragments.add("context-generation");
            fileOrPathFragments.add("contextgeneration");
            domainIntents.add("service");
        }
        if (evidenceCoverage) {
            englishKeywords.add("evidence");
            englishKeywords.add("coverage");
            simpleNames.add("evidence");
            simpleNames.add("coverage");
            fileOrPathFragments.add("evidence-coverage");
            fileOrPathFragments.add("evidencecoverage");
            fileOrPathFragments.add("coverage");
            camelCaseNames.add("EvidenceCoverage");
            camelCaseNames.add("EvidenceCoverageApi");
            camelCaseNames.add("EvidenceCoverageController");
            camelCaseNames.add("EvidenceCoverageService");
            camelCaseNames.add("EvidenceCoverageSummary");
            camelCaseNames.add("EvidenceCoverageReport");
            camelCaseNames.add("EvidenceCoverageResult");
            camelCaseNames.add("ProjectEvidenceCoverage");
            camelCaseNames.add("ProjectEvidenceCoverageSummary");
            camelCaseNames.add("ProjectContextController");
            if (normalizedQuestion.contains("knowledge") || normalizedQuestion.contains("source index") || normalizedQuestion.contains("index")) {
                camelCaseNames.add("KnowledgeCoverageService");
                camelCaseNames.add("KnowledgeIndexApplicationService");
                camelCaseNames.add("KnowledgeIndexResult");
                camelCaseNames.add("KnowledgeController");
            } else {
                camelCaseNames.add("ProjectEvidenceCatalogApplicationService");
                camelCaseNames.add("ProjectEvidenceCatalogTests");
                fileOrPathFragments.add("project-evidence-catalog");
                fileOrPathFragments.add("projectevidencecatalog");
            }
            if (englishKeywords.contains("project")) {
                camelCaseNames.add("ProjectEvidenceCoverage");
                camelCaseNames.add("ProjectEvidenceCoverageSummary");
                camelCaseNames.add("ProjectContextController");
            }
            domainIntents.add("controller");
            domainIntents.add("service");
        }

        boolean queryPlan = normalizedQuestion.contains("queryplan")
                || normalizedQuestion.contains("query plan")
                || camelCaseNames.stream().map(this::normalize).anyMatch(name -> name.contains("queryplan"));
        boolean requiredEvidence = normalizedQuestion.contains("requiredevidence")
                || normalizedQuestion.contains("required evidence");
        if (queryPlan || requiredEvidence) {
            englishKeywords.add("query");
            englishKeywords.add("plan");
            englishKeywords.add("planner");
            simpleNames.add("query");
            simpleNames.add("plan");
            simpleNames.add("planner");
            camelCaseNames.add("QueryPlan");
            camelCaseNames.add("KnowledgeQueryPlan");
            camelCaseNames.add("KnowledgeQueryPlanner");
            fileOrPathFragments.add("queryplan");
            fileOrPathFragments.add("query-plan");
            domainIntents.add("service");
        }
        if (requiredEvidence) {
            englishKeywords.add("required");
            englishKeywords.add("evidence");
            simpleNames.add("required");
            simpleNames.add("evidence");
            camelCaseNames.add("requiredEvidence");
            camelCaseNames.add("RequiredEvidence");
            fileOrPathFragments.add("requiredevidence");
            fileOrPathFragments.add("required-evidence");
        }
        boolean sourceEvidenceLoop = normalizedQuestion.contains("source evidence loop")
                || normalizedQuestion.contains("sourceevidenceloop")
                || normalizedQuestion.contains("evidence pack");
        if (sourceEvidenceLoop) {
            englishKeywords.add("source");
            englishKeywords.add("evidence");
            englishKeywords.add("loop");
            englishKeywords.add("pack");
            simpleNames.add("source");
            simpleNames.add("evidence");
            simpleNames.add("loop");
            simpleNames.add("pack");
            camelCaseNames.add("SourceEvidenceLoop");
            camelCaseNames.add("EvidencePack");
            fileOrPathFragments.add("source-evidence-loop");
            fileOrPathFragments.add("sourceevidenceloop");
            domainIntents.add("service");
        }
    }

    private void addGeneratedCamelForms(Set<String> camelCaseNames, List<String> englishKeywords) {
        if (englishKeywords.isEmpty()) {
            return;
        }
        if (englishKeywords.size() >= 2) {
            camelCaseNames.add(pascalCase(englishKeywords));
        }
        for (int i = 0; i < englishKeywords.size() - 1; i++) {
            camelCaseNames.add(pascalCase(englishKeywords.subList(i, Math.min(i + 2, englishKeywords.size()))));
        }
        for (int i = 0; i < englishKeywords.size() - 2; i++) {
            camelCaseNames.add(pascalCase(englishKeywords.subList(i, Math.min(i + 3, englishKeywords.size()))));
        }
    }

    private String pascalCase(List<String> words) {
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            builder.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                builder.append(word.substring(1));
            }
        }
        return builder.toString();
    }

    private void inferDomainIntents(
            String normalizedQuestion,
            Set<String> chineseKeywords,
            Set<String> englishKeywords,
            Set<String> fileOrPathFragments,
            Set<String> domainIntents
    ) {
        addDomainIntentIfAny(domainIntents, "sql", normalizedQuestion, englishKeywords, fileOrPathFragments, "sql");
        addDomainIntentIfAny(domainIntents, "schema", normalizedQuestion, englishKeywords, fileOrPathFragments, "schema", "ddl");
        addDomainIntentIfAny(domainIntents, "repository", normalizedQuestion, englishKeywords, fileOrPathFragments, "repository", "jdbc", "mapper", "dao");
        addDomainIntentIfAny(domainIntents, "controller", normalizedQuestion, englishKeywords, fileOrPathFragments, "controller", "endpoint", "api");
        addDomainIntentIfAny(domainIntents, "service", normalizedQuestion, englishKeywords, fileOrPathFragments, "service", "implementation", "implemented", "generate", "generation");
        addDomainIntentIfAny(domainIntents, "review", normalizedQuestion, englishKeywords, fileOrPathFragments, "review");
        addDomainIntentIfAny(domainIntents, "feedback", normalizedQuestion, englishKeywords, fileOrPathFragments, "feedback", "false_positive", "positive");
        addDomainIntentIfAny(domainIntents, "llm", normalizedQuestion, englishKeywords, fileOrPathFragments, "llm");
        addDomainIntentIfAny(domainIntents, "settings", normalizedQuestion, englishKeywords, fileOrPathFragments, "settings", "configuration", "config");
        addDomainIntentIfAny(domainIntents, "provider", normalizedQuestion, englishKeywords, fileOrPathFragments, "provider", "model", "key", "api-key");
        addDomainIntentIfAny(domainIntents, "timeout", normalizedQuestion, englishKeywords, fileOrPathFragments, "timeout");
        addDomainIntentIfAny(domainIntents, "test", normalizedQuestion, englishKeywords, fileOrPathFragments, "test", "tests", "smoke");
        if (normalizedQuestion.contains("database") || normalizedQuestion.contains("persistence") || chineseKeywords.contains("数据库") || chineseKeywords.contains("持久")) {
            domainIntents.add("database");
            domainIntents.add("persistence");
        }
        if (chineseKeywords.contains("上下文") || chineseKeywords.contains("生成") || chineseKeywords.contains("实现")) {
            domainIntents.add("service");
        }
        if (chineseKeywords.contains("反馈") || chineseKeywords.contains("后续")) {
            domainIntents.add("review");
            domainIntents.add("feedback");
        }
        if (chineseKeywords.contains("配置")) {
            domainIntents.add("settings");
        }
        if (chineseKeywords.contains("测试")) {
            domainIntents.add("test");
        }
    }

    private void addDomainIntentIfAny(
            Set<String> domainIntents,
            String intent,
            String normalizedQuestion,
            Set<String> englishKeywords,
            Set<String> fileOrPathFragments,
            String... markers
    ) {
        for (String marker : markers) {
            String normalizedMarker = normalize(marker);
            if (normalizedQuestion.contains(normalizedMarker)
                    || englishKeywords.contains(normalizedMarker)
                    || fileOrPathFragments.stream().map(this::normalize).anyMatch(fragment -> fragment.contains(normalizedMarker))) {
                domainIntents.add(intent);
                return;
            }
        }
    }

    private EvidenceContract implementationContract() {
        return new EvidenceContract("implementation_detail", List.of(
                new EvidenceGroupSpec("controller_entrypoint", List.of(
                        spec("controller_entrypoint",
                                "controller entrypoint",
                                "HTTP controller or entrypoint for the requested workflow",
                                List.of("@RestController", "@RequestMapping", "@PostMapping", "@GetMapping"),
                                List.of("controller", "endpoint", "api", "generate", "context", "entrypoint"))
                )),
                new EvidenceGroupSpec("application_service", List.of(
                        spec("application_service",
                                "application service",
                                "main application service implementation for the requested workflow",
                                List.of("@Service", "public ", "generate", "return"),
                                List.of("service", "application", "implementation", "generate", "context"))
                )),
                new EvidenceGroupSpec("key_model_result", List.of(
                        spec("key_model_result",
                                "result model",
                                "result or DTO returned by the requested workflow",
                                List.of("record ", "class ", "Result", "Response"),
                                List.of("result", "response", "dto", "model", "plan", "context")),
                        spec("key_model_result",
                                "domain model",
                                "domain object used by the requested workflow",
                                List.of("record ", "class "),
                                List.of("asset", "model", "plan", "domain", "context"))
                )),
                new EvidenceGroupSpec("test_or_contract", List.of(
                        spec("test_or_contract",
                                "workflow test",
                                "test or contract for the requested workflow",
                                List.of("@Test", "assertThat", "MockMvc", "assert"),
                                List.of("test", "contract", "generate", "context"))
                ))
        ));
    }

    private EvidenceContract databaseContract() {
        return new EvidenceContract("database_detail", List.of(
                new EvidenceGroupSpec("schema", List.of(
                        spec("schema",
                                "database schema",
                                "SQL schema file defining database tables",
                                List.of("CREATE TABLE", "CREATE INDEX", "ALTER TABLE"),
                                List.of("schema", ".sql", "create table", "database"))
                )),
                new EvidenceGroupSpec("repository_sql", List.of(
                        spec("repository_sql",
                                "JDBC repository",
                                "repository or mapper containing persistence SQL",
                                List.of("JdbcTemplate", "INSERT INTO", "SELECT ", "UPDATE ", "DELETE FROM"),
                                List.of("repository", "jdbc", "mapper", "insert into", "select")),
                        spec("repository_sql",
                                "SQL mapper",
                                "XML or mapper SQL persistence implementation",
                                List.of("<select", "<insert", "SELECT ", "INSERT INTO"),
                                List.of("mapper", "repository", "sql", "select", "insert"))
                )),
                new EvidenceGroupSpec("entity_or_model", List.of(
                        spec("entity_or_model",
                                "persistence model",
                                "entity or record persisted by the database layer",
                                List.of("record ", "class "),
                                List.of("entity", "model", "record", "domain", "document")),
                        spec("entity_or_model",
                                "database row model",
                                "row or DTO object used by repository SQL",
                                List.of("record ", "class "),
                                List.of("row", "dto", "model", "record"))
                )),
                new EvidenceGroupSpec("migration_runner", List.of(
                        spec("migration_runner",
                                "schema migration runner",
                                "runtime schema migration or initializer",
                                List.of("@PostConstruct", "migrate", "jdbcTemplate.execute", "CREATE TABLE", "ALTER TABLE"),
                                List.of("migration", "schema", "runner", "initializer", "create table"))
                ))
        ));
    }

    private EvidenceContract reviewContextContract() {
        return new EvidenceContract("review_context_detail", List.of(
                new EvidenceGroupSpec("review_application_service", List.of(
                        spec("review_application_service",
                                "review application service",
                                "review application workflow service",
                                List.of("createReview", "review", "postProcessor", "feedback"),
                                List.of("review", "service", "create", "workflow"))
                )),
                new EvidenceGroupSpec("feedback_memory_signal", List.of(
                        spec("feedback_memory_signal",
                                "feedback memory signal",
                                "mapping from user feedback to later review memory signal",
                                List.of("false_positive", "FALSE_POSITIVE", "feedback", "signal"),
                                List.of("feedback", "memory", "signal", "false_positive")),
                        spec("feedback_memory_signal",
                                "feedback signal model",
                                "feedback memory signal model",
                                List.of("record ", "enum ", "signal"),
                                List.of("feedback", "memory", "signal", "model"))
                )),
                new EvidenceGroupSpec("postprocessor_filtering", List.of(
                        spec("postprocessor_filtering",
                                "review postprocessor filtering",
                                "postprocessor or filter that applies feedback to review issues",
                                List.of("downgrade", "filter", "noise", "feedback"),
                                List.of("postprocessor", "processor", "filter", "feedback", "downgrade"))
                )),
                new EvidenceGroupSpec("review_issue_model", List.of(
                        spec("review_issue_model",
                                "review issue model",
                                "review issue or feedback model",
                                List.of("record ", "class ", "enum "),
                                List.of("review", "issue", "model", "status")),
                        spec("review_issue_model",
                                "review feedback type",
                                "review feedback or memory signal type",
                                List.of("false_positive", "enum ", "type"),
                                List.of("feedback", "signal", "type", "false_positive"))
                )),
                new EvidenceGroupSpec("test_or_smoke", List.of(
                        spec("test_or_smoke",
                                "review feedback smoke test",
                                "test proving feedback affects later review behavior",
                                List.of("@Test", "false_positive", "feedback", "assertThat"),
                                List.of("review", "feedback", "false_positive", "smoke", "test")),
                        spec("test_or_smoke",
                                "review postprocessor test",
                                "review postprocessor feedback test",
                                List.of("@Test", "downgrade", "feedback", "assertThat"),
                                List.of("review", "postprocessor", "feedback", "test"))
                ))
        ));
    }

    private EvidenceContract configurationContract() {
        return new EvidenceContract("configuration_detail", List.of(
                new EvidenceGroupSpec("properties", List.of(
                        spec("properties",
                                "LLM properties",
                                "properties object for provider/model/key/timeout",
                                List.of("ConfigurationProperties", "apiKey", "timeout", "provider", "model"),
                                List.of("properties", "configuration", "llm", "api-key", "timeout", "model")),
                        spec("properties",
                                "application config file",
                                "application YAML/properties values for provider/key/timeout",
                                List.of("provider", "timeout", "apiKey", "api-key", "apiKeyEnv"),
                                List.of("application.yml", "application.yaml", "application.properties", "provider", "timeout", "api-key"))
                )),
                new EvidenceGroupSpec("settings_controller", List.of(
                        spec("settings_controller",
                                "settings controller",
                                "settings API controller for provider/model/key/timeout",
                                List.of("@RestController", "@RequestMapping", "settings", "@PostMapping", "@PutMapping"),
                                List.of("settings", "controller", "llm", "api-key", "timeout", "test"))
                )),
                new EvidenceGroupSpec("local_config_store", List.of(
                        spec("local_config_store",
                                "local settings store",
                                "local config writer and reader",
                                List.of("save", "load", "api-key", "apiKey"),
                                List.of("store", "local", "settings", "config", "save", "load"))
                )),
                new EvidenceGroupSpec("provider_client", List.of(
                        spec("provider_client",
                                "provider client",
                                "concrete LLM provider client",
                                List.of("Authorization", "timeout", "apiKey", "model"),
                                List.of("client", "provider", "llm", "authorization", "timeout")),
                        spec("provider_client",
                                "LLM client",
                                "LLM client implementation",
                                List.of("chat", "apiKey", "timeout", "model"),
                                List.of("llm", "client", "provider", "api-key", "timeout"))
                )),
                new EvidenceGroupSpec("test_endpoint", List.of(
                        spec("test_endpoint",
                                "provider test endpoint",
                                "provider connection test endpoint or service",
                                List.of("testConnection", "connectionCheck", "llmClient.chat", "/test"),
                                List.of("test", "connection", "endpoint", "provider", "llm")),
                        spec("test_endpoint",
                                "provider test case",
                                "test for provider connection endpoint",
                                List.of("@Test", "/test", "lastRequest", "assertThat"),
                                List.of("settings", "provider", "connection", "test"))
                ))
        ));
    }

    private CandidateSpec spec(
            String evidenceGroup,
            String symbol,
            String reason,
            List<String> markers,
            List<String> searchTerms
    ) {
        return new CandidateSpec(evidenceGroup, symbol, reason, markers, searchTerms);
    }

    private List<CandidatePlan> seedCandidates(
            Path root,
            DiscoveryCorpus corpus,
            EvidenceContract contract,
            QueryFocus queryFocus,
            Set<String> seenCandidates
    ) {
        List<CandidatePlan> result = new ArrayList<>();
        int rank = 1;
        for (EvidenceGroupSpec group : contract.groups()) {
            List<CandidatePlan> discovered = discoverCandidates(root, corpus, group, queryFocus, "seed_dynamic_discovery", false, seenCandidates);
            for (CandidatePlan plan : discovered) {
                Candidate candidate = plan.candidate();
                result.add(new CandidatePlan(
                        new Candidate(
                                candidate.path(),
                                candidate.symbol(),
                                candidate.matchedReason(),
                                candidate.expectedEvidenceGroup(),
                                rank++
                        ),
                        plan.markers(),
                        plan.searchTerms()
                ));
            }
        }
        return result;
    }

    private List<CandidatePlan> expansionCandidates(
            Path root,
            DiscoveryCorpus corpus,
            EvidenceContract contract,
            QueryFocus queryFocus,
            List<String> groupsToFind,
            List<EvidenceFragment> evidencePack,
            Set<String> seenCandidates
    ) {
        List<CandidatePlan> result = new ArrayList<>();
        int rank = 1;
        for (EvidenceGroupSpec group : contract.groups()) {
            if (!groupsToFind.contains(group.name())) {
                continue;
            }
            List<CandidatePlan> discovered = new ArrayList<>();
            if ("entity_or_model".equals(group.name())) {
                discovered.addAll(repositoryModelExpansionCandidates(corpus, evidencePack, seenCandidates));
            }
            discovered.addAll(discoverCandidates(root, corpus, group, queryFocus, "expand_missing_evidence", true, seenCandidates));
            for (CandidatePlan plan : discovered) {
                Candidate candidate = plan.candidate();
                result.add(new CandidatePlan(
                        new Candidate(
                                candidate.path(),
                                candidate.symbol(),
                                candidate.matchedReason(),
                                candidate.expectedEvidenceGroup(),
                                rank++
                        ),
                        plan.markers(),
                        plan.searchTerms()
                ));
            }
        }
        return result;
    }

    private List<CandidatePlan> discoverCandidates(
            Path root,
            DiscoveryCorpus corpus,
            EvidenceGroupSpec group,
            QueryFocus queryFocus,
            String phase,
            boolean includeProviderSearch,
            Set<String> seenCandidates
    ) {
        List<CandidatePlan> result = new ArrayList<>();
        LinkedHashSet<String> groupSeen = new LinkedHashSet<>();
        for (CandidateSpec spec : group.candidates()) {
            List<DiscoveredPath> discoveredPaths = new ArrayList<>();
            discoveredPaths.addAll(testCompanionPathCandidates(spec, queryFocus, corpus));
            discoveredPaths.addAll(exactFocusPathCandidates(spec, queryFocus, corpus));
            discoveredPaths.addAll(sourceRepoMapCandidates(spec, queryFocus, corpus));
            if (includeProviderSearch) {
                discoveredPaths.addAll(readOnlyProviderSearchCandidates(root, spec, queryFocus));
            }
            List<DiscoveredPath> ranked = discoveredPaths.stream()
                    .collect(LinkedHashMap<String, DiscoveredPath>::new, (map, discovered) -> {
                        DiscoveredPath existing = map.get(discovered.path());
                        if (existing == null || discovered.score() > existing.score()) {
                            map.put(discovered.path(), discovered);
                        }
                    }, LinkedHashMap::putAll)
                    .values()
                    .stream()
                    .sorted(Comparator
                            .comparingDouble(DiscoveredPath::score)
                            .reversed()
                            .thenComparing(DiscoveredPath::path))
                    .toList();
            int addedForSpec = 0;
            for (DiscoveredPath discovered : ranked) {
                if (!groupSeen.add(discovered.path())) {
                    continue;
                }
                Candidate candidate = new Candidate(
                        discovered.path(),
                        spec.symbol(),
                        phase + ":" + discovered.reason() + ":" + spec.reason(),
                        spec.evidenceGroup(),
                        result.size() + 1
                );
                if (seenCandidates.add(candidateKey(candidate))) {
                    result.add(new CandidatePlan(candidate, evidenceMarkers(spec, queryFocus), discoveryTerms(spec, queryFocus)));
                    addedForSpec++;
                }
                if (result.size() >= MAX_DISCOVERY_CANDIDATES_PER_GROUP) {
                    return result;
                }
                if (addedForSpec >= MAX_DISCOVERY_CANDIDATES_PER_SPEC) {
                    break;
                }
            }
        }
        return result;
    }

    private List<DiscoveredPath> testCompanionPathCandidates(
            CandidateSpec spec,
            QueryFocus queryFocus,
            DiscoveryCorpus corpus
    ) {
        if (queryFocus == null
                || corpus == null
                || !("test_or_contract".equals(spec.evidenceGroup())
                || "test_or_smoke".equals(spec.evidenceGroup())
                || "test_endpoint".equals(spec.evidenceGroup()))
                || !queryNeedsTestEvidence(queryFocus)) {
            return List.of();
        }
        List<DiscoveredPath> result = new ArrayList<>();
        for (CorpusFile file : corpus.files()) {
            String path = normalize(file.relativePath());
            if (!isTestPath(path) || queryExplicitlyRejectsPath(path, queryFocus)) {
                continue;
            }
            double score = explicitTestCompanionBoost(path, spec.evidenceGroup(), queryFocus);
            if (score > 0.0 && coversCandidateRole(file.relativePath(), spec)) {
                result.add(new DiscoveredPath(file.relativePath(), 40_000.0 + score, "explicit_test_companion"));
            }
        }
        return result;
    }

    private List<String> evidenceMarkers(CandidateSpec spec, QueryFocus queryFocus) {
        LinkedHashSet<String> markers = new LinkedHashSet<>();
        for (String term : querySpecificFocusTerms(queryFocus)) {
            addDiscoveryTerm(markers, term);
        }
        if (queryPlanTarget(queryFocus)) {
            addDiscoveryTerm(markers, "addRequiredEvidenceForIntent");
            addDiscoveryTerm(markers, "requiredEvidenceTypes");
            addDiscoveryTerm(markers, "new KnowledgeQueryPlan");
            addDiscoveryTerm(markers, "required.stream().toList");
        }
        if (queryMentionsSourceEvidenceLoop(queryFocus)) {
            addDiscoveryTerm(markers, "selectEvidencePack");
            addDiscoveryTerm(markers, "SourceEvidenceLoopProbe");
            addDiscoveryTerm(markers, "EvidencePack");
        }
        spec.markers().forEach(term -> addDiscoveryTerm(markers, term));
        return markers.stream().toList();
    }

    private List<DiscoveredPath> exactFocusPathCandidates(
            CandidateSpec spec,
            QueryFocus queryFocus,
            DiscoveryCorpus corpus
    ) {
        if (queryFocus == null || corpus == null) {
            return List.of();
        }
        List<String> focusTerms = querySpecificFocusTerms(queryFocus).stream()
                .map(this::compact)
                .filter(term -> term.length() >= 10)
                .distinct()
                .toList();
        if (focusTerms.isEmpty()) {
            return List.of();
        }
        List<DiscoveredPath> result = new ArrayList<>();
        for (CorpusFile file : corpus.files()) {
            if (queryExplicitlyRejectsPath(normalize(file.relativePath()), queryFocus)) {
                continue;
            }
            String compactPath = compact(file.relativePath());
            double bestScore = 0.0;
            for (String term : focusTerms) {
                if (compactPath.contains(term)) {
                    bestScore = Math.max(bestScore, 8_000.0 + term.length());
                }
            }
            if (bestScore > 0.0 && coversCandidateRole(file.relativePath(), spec)) {
                result.add(new DiscoveredPath(file.relativePath(), bestScore, "exact_focus_path"));
            }
        }
        return result;
    }

    private boolean coversCandidateRole(String relativePath, CandidateSpec spec) {
        String path = normalize(relativePath);
        return switch (spec.evidenceGroup()) {
            case "controller_entrypoint", "settings_controller" ->
                    isJavaPath(path) && path.contains("controller");
            case "application_service", "review_application_service", "feedback_memory_signal",
                    "postprocessor_filtering", "local_config_store" ->
                    isJavaPath(path) && !isTestPath(path)
                            && (path.contains("service")
                            || path.contains("adapter")
                            || path.contains("provider")
                            || path.contains("planner")
                            || path.contains("probe")
                            || path.contains("recorder")
                            || path.contains("store")
                            || path.contains("processor"));
            case "test_endpoint" ->
                    isJavaPath(path)
                            && (isTestPath(path)
                            || path.contains("controller")
                            || path.contains("service"));
            case "key_model_result", "review_issue_model", "entity_or_model" ->
                    isJavaPath(path) && !isTestPath(path)
                            && (path.contains("/domain/") || path.contains("/model/"));
            case "test_or_contract", "test_or_smoke" ->
                    isJavaPath(path) && isTestPath(path);
            case "schema" -> path.endsWith(".sql");
            case "migration_runner" ->
                    isJavaPath(path) && !isTestPath(path)
                            && (path.contains("migration") || path.contains("runner"));
            case "repository_sql", "provider_client", "read_only_provider", "trace_recorder" ->
                    isJavaPath(path) && !isTestPath(path);
            case "properties" -> path.endsWith(".properties") || path.endsWith(".yml") || path.endsWith(".yaml");
            default -> isJavaPath(path) || path.endsWith(".sql") || path.endsWith(".properties")
                    || path.endsWith(".yml") || path.endsWith(".yaml");
        };
    }

    private List<CandidatePlan> repositoryModelExpansionCandidates(
            DiscoveryCorpus corpus,
            List<EvidenceFragment> evidencePack,
            Set<String> seenCandidates
    ) {
        List<CandidatePlan> result = new ArrayList<>();
        for (EvidenceFragment fragment : evidencePack == null ? List.<EvidenceFragment>of() : evidencePack) {
            if (!"repository_sql".equals(fragment.evidenceGroup())) {
                continue;
            }
            for (String modelName : repositoryModelNames(fragment.content())) {
                Optional<String> modelPath = findModelPath(corpus, modelName);
                if (modelPath.isEmpty()) {
                    continue;
                }
                Candidate candidate = new Candidate(
                        modelPath.get(),
                        "persistence model",
                        "expand_missing_evidence:repository_model_reference:" + fragment.path(),
                        "entity_or_model",
                        result.size() + 1
                );
                if (seenCandidates.add(candidateKey(candidate))) {
                    result.add(new CandidatePlan(
                            candidate,
                            List.of("record " + modelName, "class " + modelName, modelName),
                            List.of(modelName, "record", "model", "entity")
                    ));
                }
            }
        }
        return result;
    }

    private List<String> repositoryModelNames(String repositoryContent) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addPatternMatches(names, repositoryContent, "\\bRowMapper\\s*<\\s*([A-Z][A-Za-z0-9_]*)\\s*>");
        addPatternMatches(names, repositoryContent, "\\bnew\\s+([A-Z][A-Za-z0-9_]*)\\s*\\(");
        return names.stream()
                .filter(name -> !Set.of("String", "Long", "Integer", "Instant", "PreparedStatement").contains(name))
                .toList();
    }

    private void addPatternMatches(Set<String> names, String content, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(content == null ? "" : content);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
    }

    private Optional<String> findModelPath(DiscoveryCorpus corpus, String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }
        return corpus.files().stream()
                .filter(file -> isJavaPath(file.relativePath()))
                .filter(file -> !isTestPath(file.relativePath()))
                .filter(file -> !normalize(file.relativePath()).contains("repository"))
                .filter(file -> normalize(file.relativePath()).contains("/domain/")
                        || normalize(file.relativePath()).contains("/model/"))
                .filter(file -> modelName.equals(primaryJavaTypeName(file.content())))
                .map(CorpusFile::relativePath)
                .sorted(Comparator
                        .comparing((String path) -> normalize(path).contains("/domain/") ? 0 : 1)
                        .thenComparing(path -> path))
                .findFirst();
    }

    private String primaryJavaTypeName(String content) {
        Matcher matcher = Pattern.compile("\\b(?:record|class|interface|enum)\\s+([A-Z][A-Za-z0-9_]*)")
                .matcher(content == null ? "" : content);
        return matcher.find() ? matcher.group(1) : "";
    }

    private List<CandidatePlan> interleaveByEvidenceGroup(List<CandidatePlan> plans, List<String> groupOrder) {
        LinkedHashMap<String, List<CandidatePlan>> byGroup = new LinkedHashMap<>();
        for (String group : groupOrder) {
            byGroup.put(group, new ArrayList<>());
        }
        for (CandidatePlan plan : plans) {
            byGroup.computeIfAbsent(plan.candidate().expectedEvidenceGroup(), ignored -> new ArrayList<>()).add(plan);
        }
        List<CandidatePlan> result = new ArrayList<>();
        int index = 0;
        boolean added;
        do {
            added = false;
            for (List<CandidatePlan> groupPlans : byGroup.values()) {
                if (index < groupPlans.size()) {
                    result.add(groupPlans.get(index));
                    added = true;
                }
            }
            index++;
        } while (added);
        return result;
    }

    private List<DiscoveredPath> sourceRepoMapCandidates(CandidateSpec spec, QueryFocus queryFocus, DiscoveryCorpus corpus) {
        List<DiscoveredPath> result = new ArrayList<>();
        for (CorpusFile file : corpus.files()) {
            String relativePath = file.relativePath();
            String normalizedPath = normalize(relativePath);
            if (isSourceEvidenceLoopProbePath(normalizedPath) && !queryMentionsSourceEvidenceLoop(queryFocus)) {
                continue;
            }
            if (queryExplicitlyRejectsPath(normalizedPath, queryFocus)) {
                continue;
            }
            double score = scorePath(relativePath, spec, queryFocus);
            score += scoreContent(file.content(), spec, queryFocus);
            score += evidenceGroupPathBoost(relativePath, spec.evidenceGroup());
            score += querySpecificPathBoost(relativePath, spec.evidenceGroup(), queryFocus);
            score += queryRolePathBoost(relativePath, spec.evidenceGroup(), queryFocus);
            if (isTestPath(relativePath) && !spec.evidenceGroup().startsWith("test_")) {
                score -= 60.0;
            }
            if (isModelEvidenceGroup(spec.evidenceGroup()) && normalize(relativePath).contains("repository")) {
                score -= 120.0;
            }
            if ("entity_or_model".equals(spec.evidenceGroup())) {
                if (normalizedPath.contains("/adapters/") || normalizedPath.contains("/persistence/")) {
                    score -= 100.0;
                }
                if (normalizedPath.contains("/domain/") || normalizedPath.contains("/model/")) {
                    score += 90.0;
                }
            }
            if ("review_issue_model".equals(spec.evidenceGroup()) && !normalize(relativePath).contains("/domain/")) {
                score -= 70.0;
            }
            if ("schema".equals(spec.evidenceGroup()) && !normalizedPath.endsWith(".sql")) {
                score -= 260.0;
            }
            if (score > 0.0) {
                result.add(new DiscoveredPath(relativePath, score, "source_repo_map_symbol_search"));
            }
        }
        return result;
    }

    private List<DiscoveredPath> readOnlyProviderSearchCandidates(Path root, CandidateSpec spec, QueryFocus queryFocus) {
        List<DiscoveredPath> result = new ArrayList<>();
        for (String term : discoveryTerms(spec, queryFocus).stream().limit(5).toList()) {
            if (term == null || term.isBlank()) {
                continue;
            }
            ReadOnlyContextSearchResult searchResult = readOnlyContextProvider.searchFiles(new ReadOnlyContextFileSearchRequest(
                    null,
                    root,
                    term,
                    ReadOnlyContextBudget.search(40, MAX_SEARCH_FILES, 24_000, 600),
                    "source_evidence_loop_probe_candidate_discovery"
            ));
            if (!"finished".equals(searchResult.status()) && !"skipped".equals(searchResult.status())) {
                continue;
            }
            for (ReadOnlyContextSearchMatch match : searchResult.matches()) {
                String relativePath = match.relativePath();
                if (!isAllowedEvidenceRelativePath(relativePath) || !isSearchable(relativePath)) {
                    continue;
                }
                double score = 25.0
                        + scorePath(relativePath, spec, queryFocus)
                        + evidenceGroupPathBoost(relativePath, spec.evidenceGroup())
                        + scoreSnippet(match.snippet(), spec, queryFocus);
                result.add(new DiscoveredPath(relativePath, score, "read_only_provider_search:" + term));
            }
        }
        return result;
    }

    private DiscoveryCorpus buildDiscoveryCorpus(Path root) {
        return discoveryCorpusCache.computeIfAbsent(root, this::buildDiscoveryCorpusUncached);
    }

    private DiscoveryCorpus buildDiscoveryCorpusUncached(Path root) {
        List<CorpusFile> files = allowedEvidenceFiles(root, MAX_SEARCH_FILES).stream()
                .map(path -> new CorpusFile(relativePath(root, path), readFileForDiscovery(path)))
                .toList();
        return new DiscoveryCorpus(files);
    }

    private List<String> discoveryTerms(CandidateSpec spec, QueryFocus queryFocus) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        queryFocus.fileOrPathFragments().forEach(term -> addDiscoveryTerm(terms, term));
        queryFocus.camelCaseNames().forEach(term -> addDiscoveryTerm(terms, term));
        queryFocus.simpleNames().forEach(term -> addDiscoveryTerm(terms, term));
        queryFocus.englishKeywords().forEach(term -> addDiscoveryTerm(terms, term));
        queryFocus.chineseKeywords().forEach(term -> addDiscoveryTerm(terms, term));
        queryFocus.domainIntents().forEach(term -> addDiscoveryTerm(terms, term));
        addDiscoveryTerm(terms, spec.symbol());
        if (spec.symbol() != null) {
            int dot = spec.symbol().lastIndexOf('.');
            if (dot >= 0 && dot + 1 < spec.symbol().length()) {
                addDiscoveryTerm(terms, spec.symbol().substring(dot + 1));
            }
        }
        spec.markers().forEach(term -> addDiscoveryTerm(terms, term));
        spec.searchTerms().forEach(term -> addDiscoveryTerm(terms, term));
        return terms.stream().toList();
    }

    private void addDiscoveryTerm(Set<String> terms, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String trimmed = value.trim();
        terms.add(trimmed);
        String compact = compact(trimmed);
        if (compact.length() >= 8) {
            terms.add(compact);
        }
    }

    private double scorePath(String relativePath, CandidateSpec spec, QueryFocus queryFocus) {
        String normalizedPath = normalize(relativePath);
        String compactPath = compact(relativePath);
        double score = 0.0;
        for (String term : discoveryTerms(spec, queryFocus)) {
            String normalizedTerm = normalize(term);
            String compactTerm = compact(term);
            if (!normalizedTerm.isBlank() && normalizedPath.contains(normalizedTerm)) {
                score += normalizedTerm.length() >= 8 ? 40.0 : 12.0;
            }
            if (!compactTerm.isBlank() && compactTerm.length() >= 8 && compactPath.contains(compactTerm)) {
                score += 70.0;
            }
        }
        String symbolTail = spec.symbol();
        if (symbolTail != null && symbolTail.contains(".")) {
            symbolTail = symbolTail.substring(0, symbolTail.indexOf('.'));
        }
        if (symbolTail != null && !symbolTail.isBlank() && compactPath.contains(compact(symbolTail))) {
            score += 80.0;
        }
        return score;
    }

    private boolean isSourceEvidenceLoopProbePath(String normalizedPath) {
        return normalizedPath.endsWith("/sourceevidenceloopprobe.java");
    }

    private boolean queryMentionsSourceEvidenceLoop(QueryFocus queryFocus) {
        if (queryFocus == null) {
            return false;
        }
        String joined = String.join(" ", queryFocus.camelCaseNames()) + " "
                + String.join(" ", queryFocus.simpleNames()) + " "
                + String.join(" ", queryFocus.englishKeywords()) + " "
                + String.join(" ", queryFocus.fileOrPathFragments()) + " "
                + queryFocus.compactLowercase();
        String compact = compact(joined);
        return compact.contains("sourceevidenceloop")
                || compact.contains("evidenceloop")
                || compact.contains("sourceevidence");
    }

    private boolean queryExplicitlyRejectsPath(String normalizedPath, QueryFocus queryFocus) {
        if (queryFocus == null) {
            return false;
        }
        String question = queryFocus.originalQuestion() == null ? "" : queryFocus.originalQuestion();
        String rejectedSegment = rejectedSegment(question);
        if (rejectedSegment.isBlank()) {
            return false;
        }
        String compactQuestion = compact(question);
        String fileName = fileName(normalizedPath);
        String simpleName = fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
        return !simpleName.isBlank() && compact(rejectedSegment).contains(compact(simpleName))
                && compactQuestion.contains(compact(simpleName));
    }

    private String rejectedSegment(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        String lower = normalize(question);
        for (String marker : List.of("不要选择", "不要选", "不选", "do not select", "don't select")) {
            int index = lower.indexOf(normalize(marker));
            if (index >= 0) {
                int start = index + marker.length();
                String tail = question.substring(Math.min(start, question.length()));
                int end = firstBoundary(tail);
                return end >= 0 ? tail.substring(0, end) : tail;
            }
        }
        return "";
    }

    private int firstBoundary(String value) {
        int best = -1;
        for (String boundary : List.of(";", "；", ",", "，", ".", "。", "?", "？")) {
            int index = value.indexOf(boundary);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private double scoreContent(String content, CandidateSpec spec, QueryFocus queryFocus) {
        if (content.isBlank()) {
            return 0.0;
        }
        String normalizedContent = normalize(content);
        String compactContent = compact(content);
        double score = 0.0;
        for (String marker : spec.markers()) {
            String normalizedMarker = normalize(marker);
            String compactMarker = compact(marker);
            if (!normalizedMarker.isBlank() && normalizedContent.contains(normalizedMarker)) {
                score += 45.0;
            }
            if (!compactMarker.isBlank() && compactMarker.length() >= 8 && compactContent.contains(compactMarker)) {
                score += 35.0;
            }
        }
        for (String term : spec.searchTerms()) {
            String normalizedTerm = normalize(term);
            String compactTerm = compact(term);
            if (!normalizedTerm.isBlank() && normalizedContent.contains(normalizedTerm)) {
                score += normalizedTerm.length() >= 8 ? 18.0 : 6.0;
            }
            if (!compactTerm.isBlank() && compactTerm.length() >= 8 && compactContent.contains(compactTerm)) {
                score += 16.0;
            }
        }
        for (String term : queryFocusTerms(queryFocus)) {
            String normalizedTerm = normalize(term);
            String compactTerm = compact(term);
            if (!normalizedTerm.isBlank() && normalizedContent.contains(normalizedTerm)) {
                score += normalizedTerm.length() >= 8 ? 14.0 : 5.0;
            }
            if (!compactTerm.isBlank() && compactTerm.length() >= 5 && compactContent.contains(compactTerm)) {
                score += compactTerm.length() >= 8 ? 18.0 : 8.0;
            }
        }
        return score;
    }

    private double scoreSnippet(String snippet, CandidateSpec spec, QueryFocus queryFocus) {
        String normalized = normalize(snippet);
        if (normalized.isBlank()) {
            return 0.0;
        }
        double score = 0.0;
        for (String marker : spec.markers()) {
            if (normalized.contains(normalize(marker))) {
                score += 35.0;
            }
        }
        for (String term : spec.searchTerms()) {
            if (normalized.contains(normalize(term))) {
                score += 12.0;
            }
        }
        for (String term : queryFocusTerms(queryFocus)) {
            if (normalized.contains(normalize(term))) {
                score += 8.0;
            }
        }
        return score;
    }

    private double evidenceGroupPathBoost(String relativePath, String evidenceGroup) {
        String path = normalize(relativePath);
        return switch (evidenceGroup) {
            case "controller_entrypoint", "settings_controller" -> rolePathBoost(path, "controller", 34.0)
                    + (path.contains("/web/") || path.contains("/adapter") ? 12.0 : 0.0);
            case "application_service", "review_application_service", "feedback_memory_signal",
                    "postprocessor_filtering", "local_config_store", "test_endpoint" ->
                    rolePathBoost(path, "service", 30.0)
                            + rolePathBoost(path, "coverage", 18.0)
                            + rolePathBoost(path, "index", 12.0)
                            + (path.contains("/application/") ? 12.0 : 0.0);
            case "schema" -> (path.endsWith(".sql") ? 70.0 : 0.0)
                    + rolePathBoost(path, "schema", 22.0);
            case "repository_sql" -> rolePathBoost(path, "repository", 38.0)
                    + rolePathBoost(path, "jdbc", 32.0)
                    + rolePathBoost(path, "mapper", 22.0)
                    + (path.contains("/persistence/") ? 18.0 : 0.0);
            case "migration_runner" -> rolePathBoost(path, "migration", 32.0)
                    + rolePathBoost(path, "schema", 24.0)
                    + rolePathBoost(path, "runner", 24.0)
                    + (path.contains("/persistence/") ? 14.0 : 0.0);
            case "entity_or_model", "key_model_result" -> rolePathBoost(path, "result", 26.0)
                    + rolePathBoost(path, "model", 22.0)
                    + rolePathBoost(path, "entity", 20.0)
                    + (path.contains("/domain/") ? 22.0 : 0.0);
            case "review_issue_model" -> rolePathBoost(path, "review", 24.0)
                    + rolePathBoost(path, "issue", 28.0)
                    + rolePathBoost(path, "signal", 24.0)
                    + rolePathBoost(path, "type", 18.0)
                    + (path.contains("/domain/") ? 80.0 : 0.0);
            case "test_or_contract", "test_or_smoke" -> isTestPath(path) || path.endsWith("test.java") || path.endsWith("tests.java") ? 95.0 : 0.0;
            case "properties" -> rolePathBoost(path, "properties", 28.0)
                    + rolePathBoost(path, "config", 18.0)
                    + (path.contains("/config/") ? 20.0 : 0.0);
            case "provider_client" -> rolePathBoost(path, "client", 28.0)
                    + rolePathBoost(path, "provider", 18.0)
                    + (path.contains("/llm/") ? 24.0 : 0.0);
            default -> 0.0;
        };
    }

    private double querySpecificPathBoost(String relativePath, String evidenceGroup, QueryFocus queryFocus) {
        String path = normalize(relativePath);
        if (!pathMatchesExplicitQueryFocus(path, queryFocus)) {
            return 0.0;
        }
        return switch (evidenceGroup) {
            case "controller_entrypoint", "settings_controller" ->
                    path.contains("controller") && !isTestPath(path) ? 700.0 : -300.0;
            case "application_service", "review_application_service", "feedback_memory_signal",
                    "postprocessor_filtering", "local_config_store" ->
                    !isTestPath(path) && containsAny(path, "service", "adapter", "provider", "store", "processor") ? 900.0 : -300.0;
            case "key_model_result", "review_issue_model", "entity_or_model" ->
                    !isTestPath(path) && !containsAny(path, "controller", "repository", "service", "adapter") ? 500.0 : -250.0;
            case "test_or_contract", "test_or_smoke", "test_endpoint" ->
                    isTestPath(path) ? 1_100.0 : -350.0;
            case "properties" ->
                    !isTestPath(path) && containsAny(path, "properties", "config") ? 600.0 : 0.0;
            case "provider_client" ->
                    !isTestPath(path) && containsAny(path, "client", "provider", "llm") ? 700.0 : -250.0;
            default -> 0.0;
        };
    }

    private boolean pathMatchesExplicitQueryFocus(String path, QueryFocus queryFocus) {
        if (queryFocus == null) {
            return false;
        }
        String compactPath = compact(path);
        for (String token : queryFocus.rawTokens()) {
            if (token.length() <= 3 || !Character.isUpperCase(token.charAt(0)) || token.equals(token.toUpperCase(Locale.ROOT))) {
                continue;
            }
            String compactToken = compact(token);
            if (compactToken.length() >= 6 && compactPath.contains(compactToken)) {
                return true;
            }
        }
        for (String camelName : queryFocus.camelCaseNames()) {
            String compactCamelName = compact(camelName);
            if (compactCamelName.length() >= 6 && compactPath.contains(compactCamelName)) {
                return true;
            }
        }
        for (String fragment : queryFocus.fileOrPathFragments()) {
            String compactFragment = compact(fragment);
            if (compactFragment.length() >= 6 && compactPath.contains(compactFragment)) {
                return true;
            }
        }
        return false;
    }

    private double rolePathBoost(String normalizedPath, String role, double boost) {
        return normalizedPath.contains(role) ? boost : 0.0;
    }

    private boolean isModelEvidenceGroup(String evidenceGroup) {
        return Set.of("entity_or_model", "key_model_result", "review_issue_model").contains(evidenceGroup);
    }

    private String readFileForDiscovery(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return content.length() > MAX_PROVIDER_READ_CHARS ? content.substring(0, MAX_PROVIDER_READ_CHARS) : content;
        } catch (IOException e) {
            return "";
        }
    }

    private Optional<EvidenceFragment> readEvidence(Path root, CandidatePlan plan, int remainingCharacters) {
        if (remainingCharacters <= 0) {
            return Optional.empty();
        }
        Candidate candidate = plan.candidate();
        Optional<Path> resolved = resolveEvidencePath(root, candidate.path());
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        List<String> lines;
        ReadOnlyContextReadResult readResult = readOnlyContextProvider.readFile(new ReadOnlyContextFileReadRequest(
                null,
                root,
                candidate.path(),
                ReadOnlyContextBudget.read(1, MAX_PROVIDER_READ_CHARS, MAX_PROVIDER_READ_LINES),
                "source_evidence_loop_probe_evidence_read"
        ));
        if (!"finished".equals(readResult.status()) || readResult.content().isBlank()) {
            return Optional.empty();
        }
        lines = readResult.content().lines().toList();
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        int markerLine = findMarkerLine(lines, plan.markers(), candidate.symbol());
        LineRange range = lineRangeFor(candidate.path(), candidate.expectedEvidenceGroup(), lines, markerLine);
        String content = renderLines(lines, range, remainingCharacters);
        if (content.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new EvidenceFragment(
                candidate.path(),
                range.start(),
                range.end(),
                candidate.expectedEvidenceGroup(),
                candidate.symbol(),
                content,
                candidate.matchedReason()
        ));
    }

    private List<EvidenceFragment> compactPrimaryEvidencePack(
            EvidenceContract contract,
            QueryFocus queryFocus,
            List<EvidenceFragment> evidencePack
    ) {
        if (evidencePack.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, List<EvidenceFragment>> byGroup = new LinkedHashMap<>();
        for (String group : contract.requiredEvidenceGroups()) {
            byGroup.put(group, new ArrayList<>());
        }
        for (EvidenceFragment fragment : evidencePack) {
            byGroup.computeIfAbsent(fragment.evidenceGroup(), ignored -> new ArrayList<>()).add(fragment);
        }

        List<EvidenceFragment> compacted = new ArrayList<>();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String group : contract.requiredEvidenceGroups()) {
            if (skipPrimaryEvidenceGroup(contract.intent(), group, queryFocus)) {
                continue;
            }
            List<EvidenceFragment> groupFragments = byGroup.getOrDefault(group, List.of());
            if (groupFragments.isEmpty()) {
                continue;
            }
            List<EvidenceFragment> coveredFragments = groupFragments.stream()
                    .filter(fragment -> coversGroup(group, queryFocus, fragment))
                    .toList();
            List<EvidenceFragment> pool = coveredFragments.isEmpty() ? groupFragments : coveredFragments;
            int limit = primaryEvidenceLimit(contract.intent(), group, queryFocus);
            pool.stream()
                    .sorted(Comparator
                            .comparingDouble((EvidenceFragment fragment) ->
                                    primaryEvidenceScore(contract.intent(), group, queryFocus, fragment))
                            .reversed()
                            .thenComparing(EvidenceFragment::path)
                            .thenComparingInt(EvidenceFragment::startLine))
                    .limit(limit)
                    .forEach(fragment -> {
                        String key = normalize(fragment.evidenceGroup()) + "|" + normalize(fragment.path());
                        if (selected.add(key)) {
                            compacted.add(fragment);
                        }
                    });
        }
        return compacted;
    }

    private int primaryEvidenceLimit(String intent, String group, QueryFocus queryFocus) {
        if ("implementation_detail".equals(intent)
                && "application_service".equals(group)
                && (multiServiceImplementationTarget(queryFocus) || implementationTraceTarget(queryFocus))) {
            return 2;
        }
        if ("implementation_detail".equals(intent)
                && "test_or_contract".equals(group)
                && answerGuardTarget(queryFocus)) {
            return 1;
        }
        if ("review_context_detail".equals(intent)
                && "test_or_smoke".equals(group)) {
            return 1;
        }
        if ("configuration_detail".equals(intent)
                && "test_endpoint".equals(group)) {
            return 1;
        }
        return 1;
    }

    private boolean skipPrimaryEvidenceGroup(String intent, String group, QueryFocus queryFocus) {
        if (!"implementation_detail".equals(intent)) {
            return false;
        }
        return switch (group) {
            case "controller_entrypoint" -> !queryNeedsControllerEvidence(queryFocus);
            case "key_model_result" -> implementationTraceTarget(queryFocus) || !queryNeedsResultModelEvidence(queryFocus);
            default -> false;
        };
    }

    private boolean queryNeedsControllerEvidence(QueryFocus queryFocus) {
        String question = normalize(queryFocus == null ? "" : queryFocus.originalQuestion());
        String compactQuestion = compact(question);
        return containsAny(question, "api", "endpoint", "controller", "entrypoint", "entry point", "route", "入口",
                "上下文", "生成")
                || compactQuestion.contains("contextgeneration")
                || compactQuestion.contains("projectgraph");
    }

    private boolean queryNeedsResultModelEvidence(QueryFocus queryFocus) {
        String question = normalize(queryFocus == null ? "" : queryFocus.originalQuestion());
        String compactQuestion = compact(question);
        return containsAny(question, "model", "result", "return", "response", "defined", "definition", "object",
                "report", "summary", "trace", "返回", "对象", "模型", "定义", "上下文", "生成")
                || compactQuestion.contains("queryplan")
                || compactQuestion.contains("requiredevidence")
                || compactQuestion.contains("contextgeneration")
                || compactQuestion.contains("projectgraph")
                || compactQuestion.contains("evidencecoverage");
    }

    private boolean queryNeedsTestEvidence(QueryFocus queryFocus) {
        String question = normalize(queryFocus == null ? "" : queryFocus.originalQuestion());
        String compactQuestion = compact(question);
        return containsAny(question, "test", "tests", "contract", "smoke", "测试")
                || compactQuestion.contains("contextgeneration")
                || compactQuestion.contains("evidencecoverageapi");
    }

    private boolean multiServiceImplementationTarget(QueryFocus queryFocus) {
        if (queryFocus == null) {
            return false;
        }
        String normalizedQuestion = normalize(queryFocus.originalQuestion());
        String compactQuestion = compact(queryFocus.originalQuestion());
        return explicitClassTargetCount(queryFocus) >= 2
                || normalizedQuestion.contains(" and ")
                || normalizedQuestion.contains(" 和 ")
                || compactQuestion.contains("rag")
                || (compactQuestion.contains("index") && compactQuestion.contains("coverage"));
    }

    private boolean queryPlanTarget(QueryFocus queryFocus) {
        if (queryFocus == null) {
            return false;
        }
        String compactQuestion = compact(queryFocus.originalQuestion());
        return compactQuestion.contains("queryplan")
                || compactQuestion.contains("requiredevidence");
    }

    private boolean answerGuardTarget(QueryFocus queryFocus) {
        if (queryFocus == null) {
            return false;
        }
        String compactQuestion = compact(queryFocus.originalQuestion());
        return compactQuestion.contains("answerguard")
                || compactQuestion.contains("supported")
                || compactQuestion.contains("partial")
                || compactQuestion.contains("insufficient");
    }

    private int explicitClassTargetCount(QueryFocus queryFocus) {
        if (queryFocus == null) {
            return 0;
        }
        return (int) queryFocus.rawTokens().stream()
                .filter(token -> token.length() > 3)
                .filter(token -> Character.isUpperCase(token.charAt(0)))
                .filter(token -> !token.equals(token.toUpperCase(Locale.ROOT)))
                .count();
    }

    private double primaryEvidenceScore(String intent, String group, QueryFocus queryFocus, EvidenceFragment fragment) {
        String path = normalize(fragment.path());
        String content = normalize(fragment.content());
        double score = 0.0;
        if (coversGroup(group, queryFocus, fragment)) {
            score += 10_000.0;
        }
        if (hasSpecificQueryFocusHit(fileName(path), "", queryFocus)) {
            score += 1_200.0;
        }
        if (hasSpecificQueryFocusHit(path, "", queryFocus)) {
            score += 600.0;
        }
        if (hasQueryFocusHit(path, content, queryFocus)) {
            score += 180.0;
        }
        score += queryMentionedTypeNameBoost(path, queryFocus);
        score += evidenceGroupPathBoost(path, group);
        score += querySpecificPathBoost(path, group, queryFocus);
        score += queryRolePathBoost(path, group, queryFocus);
        score += primaryRoleBoost(intent, group, path, content, queryFocus);

        boolean testGroup = group.startsWith("test_") || "test_or_contract".equals(group);
        if (isTestPath(path) && !testGroup) {
            score -= 450.0;
        }
        if (!isTestPath(path) && testGroup) {
            score -= 450.0;
        }
        if (path.startsWith("frontend/")) {
            score -= 350.0;
        }
        if (path.endsWith(".json") && !"properties".equals(group)) {
            score -= 300.0;
        }
        if (manualOrRealLlmAcceptanceTestPath(path)) {
            score -= 2_500.0;
        }
        return score;
    }

    private boolean manualOrRealLlmAcceptanceTestPath(String path) {
        String normalizedPath = normalize(path);
        return isTestPath(normalizedPath)
                && (normalizedPath.contains("realllm")
                || normalizedPath.contains("manual")
                || normalizedPath.contains("acceptance"));
    }

    private double queryMentionedTypeNameBoost(String path, QueryFocus queryFocus) {
        if (queryFocus == null) {
            return 0.0;
        }
        String compactPath = compact(path);
        double score = 0.0;
        for (String token : queryFocus.rawTokens()) {
            if (token.length() <= 3 || !Character.isUpperCase(token.charAt(0)) || token.equals(token.toUpperCase(Locale.ROOT))) {
                continue;
            }
            String compactToken = compact(token);
            if (!compactToken.isBlank() && compactPath.contains(compactToken)) {
                score += 1_800.0;
            }
        }
        for (String camelName : queryFocus.camelCaseNames()) {
            String compactCamelName = compact(camelName);
            if (compactCamelName.length() >= 6 && compactPath.contains(compactCamelName)) {
                score += 1_600.0;
            }
        }
        for (String simpleName : queryFocus.simpleNames()) {
            String compactSimpleName = compact(simpleName);
            if (compactSimpleName.length() >= 6 && compactPath.contains(compactSimpleName)) {
                score += 1_000.0;
            }
        }
        for (String fragment : queryFocus.fileOrPathFragments()) {
            String compactFragment = compact(fragment);
            if (compactFragment.length() >= 6 && compactPath.contains(compactFragment)) {
                score += 1_200.0;
            }
        }
        return score;
    }

    private double queryRolePathBoost(String relativePath, String evidenceGroup, QueryFocus queryFocus) {
        String path = normalize(relativePath);
        double score = explicitTestCompanionBoost(path, evidenceGroup, queryFocus);
        boolean testGroup = "test_or_contract".equals(evidenceGroup)
                || "test_or_smoke".equals(evidenceGroup)
                || "test_endpoint".equals(evidenceGroup);
        if (testGroup && answerGuardTarget(queryFocus)) {
            if (containsAny(path, "evidence", "evaluation", "guard")) {
                score += 10_000.0;
            } else if (path.contains("/v0") || path.contains("/v1") || path.contains("knowledge-rag")) {
                score -= 5_000.0;
            }
        }
        if ("application_service".equals(evidenceGroup)) {
            if (queryMentionsSandbox(queryFocus) && containsAny(path, "sandbox", "sandboxed")) {
                score += 4_500.0;
            }
            if (queryMentionsTrace(queryFocus) && containsAny(path, "trace", "recorder")) {
                score += 3_500.0;
            }
            if ((queryMentionsSandbox(queryFocus) || queryMentionsTrace(queryFocus))
                    && path.endsWith("provider.java")
                    && !containsAny(path, "sandbox", "sandboxed", "trace", "recorder")) {
                score -= 800.0;
            }
        }
        if ("key_model_result".equals(evidenceGroup)
                && implementationTraceTarget(queryFocus)
                && path.endsWith("provider.java")
                && !containsAny(path, "trace", "recorder", "result", "summary", "report")) {
            score -= 4_000.0;
        }
        return score;
    }

    private double explicitTestCompanionBoost(String path, String evidenceGroup, QueryFocus queryFocus) {
        if (queryFocus == null
                || !("test_or_contract".equals(evidenceGroup)
                || "test_or_smoke".equals(evidenceGroup)
                || "test_endpoint".equals(evidenceGroup))
                || !queryNeedsTestEvidence(queryFocus)
                || !isTestPath(path)) {
            return 0.0;
        }
        String compactFile = compact(fileName(path));
        double score = 0.0;
        for (String camelName : queryFocus.camelCaseNames()) {
            String compactName = compact(camelName);
            if (compactName.length() < 6) {
                continue;
            }
            if (compactFile.contains(compactName + "test") || compactFile.contains(compactName + "tests")) {
                score += 12_000.0;
            } else if (compactFile.contains(compactName)) {
                score += 4_000.0;
            }
        }
        return score;
    }

    private double primaryRoleBoost(
            String intent,
            String group,
            String path,
            String content,
            QueryFocus queryFocus
    ) {
        double score = switch (group) {
            case "controller_entrypoint" -> rolePathBoost(path, "controller", 500.0)
                    + rolePathBoost(path, "endpoint", 160.0)
                    + (path.contains("/adapters/web/") ? 160.0 : 0.0)
                    - (isTestPath(path) ? 400.0 : 0.0);
            case "application_service" -> rolePathBoost(path, "service", 480.0)
                    + rolePathBoost(path, "provider", 240.0)
                    + rolePathBoost(path, "adapter", 120.0)
                    + (path.contains("/application/") ? 220.0 : 0.0)
                    - (path.contains("/domain/") ? 300.0 : 0.0);
            case "key_model_result" -> rolePathBoost(path, "result", 420.0)
                    + rolePathBoost(path, "response", 300.0)
                    + rolePathBoost(path, "report", 420.0)
                    + rolePathBoost(path, "model", 260.0)
                    + rolePathBoost(path, "queryplan", 520.0)
                    + rolePathBoost(path, "plan", 360.0)
                    + rolePathBoost(path, "neighborhood", 520.0)
                    + rolePathBoost(path, "trace", 1_200.0)
                    + rolePathBoost(path, "recorder", 5_600.0)
                    + (path.contains("/domain/") ? 320.0 : 0.0)
                    + (path.contains("/domain/knowledge/") ? 260.0 : 0.0)
                    + (queryMentionsTrace(queryFocus) && path.contains("recorder") ? 4_000.0 : 0.0)
                    - (queryMentionsTrace(queryFocus) && path.contains("/domain/") && !path.contains("recorder") ? 4_000.0 : 0.0)
                    - (containsAny(path, "controller", "repository", "planner") ? 350.0 : 0.0)
                    - (path.contains("formatter") ? 1_400.0 : 0.0)
                    - (path.endsWith("service.java") ? 260.0 : 0.0);
            case "test_or_contract" -> (isTestPath(path) ? 700.0 : 0.0)
                    + rolePathBoost(path, "contract", 220.0)
                    + rolePathBoost(path, "tests", 180.0)
                    + rolePathBoost(path, "rag", 420.0)
                    + rolePathBoost(path, "evidence", 520.0)
                    + rolePathBoost(path, "evaluation", 1_400.0)
                    + rolePathBoost(path, "answer", 260.0)
                    + (answerGuardTarget(queryFocus) && path.contains("evaluation") ? 1_600.0 : 0.0)
                    + (answerGuardTarget(queryFocus) && path.contains("evidence") ? 700.0 : 0.0)
                    + (queryMentionsSourceEvidenceLoop(queryFocus) && path.contains("sourceevidenceloop") ? 20_000.0 : 0.0)
                    - (queryMentionsSourceEvidenceLoop(queryFocus) && !path.contains("sourceevidenceloop") ? 10_000.0 : 0.0);
            case "schema" -> (path.endsWith("/schema.sql") ? 900.0 : 0.0)
                    + tableNameFocusBoost(path, content, queryFocus);
            case "repository_sql" -> rolePathBoost(path, "jdbc", 520.0)
                    + rolePathBoost(path, "repository", 420.0)
                    + tableNameFocusBoost(path, content, queryFocus)
                    - (path.contains("/ports/") ? 500.0 : 0.0);
            case "entity_or_model" -> (path.contains("/domain/") ? 700.0 : 0.0)
                    + (path.contains("/model/") ? 900.0 : 0.0)
                    + rolePathBoost(path, "event", 140.0)
                    + tableNameFocusBoost(path, content, queryFocus)
                    - (rolePathBoost(path, "result", 420.0))
                    - (rolePathBoost(path, "response", 260.0))
                    - (path.contains("/adapters/") || path.contains("/persistence/") ? 400.0 : 0.0);
            case "migration_runner" -> rolePathBoost(path, "migration", 520.0)
                    + rolePathBoost(path, "runner", 320.0)
                    + rolePathBoost(path, "schema", 220.0);
            case "review_application_service" -> rolePathBoost(path, "review", 520.0)
                    + rolePathBoost(path, "application", 300.0)
                    + rolePathBoost(path, "service", 360.0)
                    - (isTestPath(path) ? 500.0 : 0.0);
            case "feedback_memory_signal" -> rolePathBoost(path, "memory", 520.0)
                    + rolePathBoost(path, "feedback", 260.0)
                    + rolePathBoost(path, "signal", 420.0)
                    + rolePathBoost(path, "service", 260.0)
                    - (isTestPath(path) ? 450.0 : 0.0);
            case "postprocessor_filtering" -> rolePathBoost(path, "postprocessor", 760.0)
                    + rolePathBoost(path, "processor", 360.0)
                    + rolePathBoost(path, "filter", 220.0)
                    - (isTestPath(path) ? 450.0 : 0.0);
            case "review_issue_model" -> (path.contains("/domain/") ? 500.0 : 0.0)
                    + rolePathBoost(path, "reviewmemorysignal", 320.0)
                    + rolePathBoost(path, "issue", 260.0)
                    + rolePathBoost(path, "type", 220.0)
                    - (isTestPath(path) ? 450.0 : 0.0);
            case "test_or_smoke" -> (isTestPath(path) ? 700.0 : 0.0)
                    + rolePathBoost(path, "benchmarksmoke", 950.0)
                    + rolePathBoost(path, "smoke", 420.0)
                    + rolePathBoost(path, "memorysignal", 260.0);
            case "properties" -> rolePathBoost(path, "properties", 2_200.0)
                    + rolePathBoost(path, "llm", 420.0)
                    + (path.contains("/config/") ? 1_200.0 : 0.0)
                    - (path.contains("/application/") ? 1_000.0 : 0.0)
                    - (path.endsWith("service.java") ? 800.0 : 0.0)
                    + (path.contains("/config/") ? 240.0 : 0.0);
            case "settings_controller" -> rolePathBoost(path, "settings", 520.0)
                    + rolePathBoost(path, "llm", 220.0)
                    + rolePathBoost(path, "controller", 420.0)
                    + (path.contains("/adapters/web/") ? 180.0 : 0.0);
            case "local_config_store" -> rolePathBoost(path, "local", 360.0)
                    + rolePathBoost(path, "llm", 220.0)
                    + rolePathBoost(path, "store", 420.0)
                    + rolePathBoost(path, "settings", 180.0);
            case "provider_client" -> rolePathBoost(path, "client", 1_600.0)
                    + rolePathBoost(path, "provider", 420.0)
                    + rolePathBoost(path, "llm", 420.0)
                    + (path.contains("/adapters/llm/") ? 2_200.0 : 0.0)
                    + (path.endsWith("client.java") ? 1_000.0 : 0.0)
                    - (path.contains("/application/") ? 1_400.0 : 0.0)
                    - (path.endsWith("service.java") ? 1_200.0 : 0.0)
                    - (path.contains("mock") || path.contains("unsupported") ? 700.0 : 0.0)
                    - (isTestPath(path) ? 450.0 : 0.0);
            case "test_endpoint" -> (isTestPath(path) ? 1_200.0 : 120.0)
                    + rolePathBoost(path, "tests", 900.0)
                    + rolePathBoost(path, "test", 520.0)
                    + rolePathBoost(path, "connectioncheck", 700.0)
                    + rolePathBoost(path, "testconnection", 520.0)
                    + rolePathBoost(path, "service", 120.0)
                    + rolePathBoost(path, "controller", 260.0)
                    + (path.contains("/application/") ? 80.0 : 0.0)
                    + (path.contains("/adapters/web/") ? 220.0 : 0.0)
                    + (isTestPath(path) ? 280.0 : 0.0);
            default -> 0.0;
        };
        if (!"implementation_detail".equals(intent) && "application_service".equals(group)) {
            score += 0.0;
        }
        return score;
    }

    private double tableNameFocusBoost(String path, String content, QueryFocus queryFocus) {
        if (queryFocus == null) {
            return 0.0;
        }
        String haystack = normalize(path) + "\n" + normalize(content);
        double score = 0.0;
        for (String token : queryFocus.rawTokens()) {
            String normalized = normalize(token);
            if (normalized.contains("_") && haystack.contains(normalized)) {
                score += 600.0;
            }
        }
        return score;
    }

    private boolean queryMentionsTrace(QueryFocus queryFocus) {
        return queryFocus != null && compact(queryFocus.originalQuestion()).contains("trace");
    }

    private boolean queryMentionsSandbox(QueryFocus queryFocus) {
        if (queryFocus == null) {
            return false;
        }
        String question = queryFocus.originalQuestion() == null ? "" : queryFocus.originalQuestion();
        String compactQuestion = compact(question);
        return compactQuestion.contains("sandbox")
                || compactQuestion.contains("sandboxed")
                || question.contains("\u6c99\u7bb1");
    }

    private boolean implementationTraceTarget(QueryFocus queryFocus) {
        return queryMentionsTrace(queryFocus) && (queryMentionsSandbox(queryFocus)
                || queryFocus.camelCaseNames().stream().map(this::compact).anyMatch(name -> name.endsWith("provider"))
                || compact(queryFocus.originalQuestion()).contains("implementation"));
    }

    private LinkedHashMap<String, String> evaluateSufficiency(EvidenceContract contract, QueryFocus queryFocus, List<EvidenceFragment> evidencePack) {
        LinkedHashMap<String, String> result = initialGroupStatus(contract);
        for (String group : result.keySet()) {
            boolean covered = evidencePack.stream()
                    .filter(fragment -> group.equals(fragment.evidenceGroup()))
                    .anyMatch(fragment -> coversGroup(group, queryFocus, fragment));
            result.put(group, covered ? COVERED : MISSING);
        }
        return result;
    }

    private LinkedHashMap<String, String> requireRepositoryLinkedModel(
            DiscoveryCorpus corpus,
            List<EvidenceFragment> evidencePack,
            LinkedHashMap<String, String> groupStatus
    ) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(groupStatus);
        if (!result.containsKey("entity_or_model") || !COVERED.equals(result.get("repository_sql"))) {
            return result;
        }
        Set<String> repositoryModelPaths = repositoryReferencedModelPaths(corpus, evidencePack);
        if (repositoryModelPaths.isEmpty()) {
            return result;
        }
        Set<String> entityModelPaths = evidencePack.stream()
                .filter(fragment -> "entity_or_model".equals(fragment.evidenceGroup()))
                .map(EvidenceFragment::path)
                .map(this::normalize)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        if (!entityModelPaths.containsAll(repositoryModelPaths)) {
            result.put("entity_or_model", MISSING);
        }
        return result;
    }

    private Set<String> repositoryReferencedModelPaths(DiscoveryCorpus corpus, List<EvidenceFragment> evidencePack) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (EvidenceFragment fragment : evidencePack == null ? List.<EvidenceFragment>of() : evidencePack) {
            if (!"repository_sql".equals(fragment.evidenceGroup())) {
                continue;
            }
            for (String modelName : repositoryModelNames(fragment.content())) {
                findModelPath(corpus, modelName).map(this::normalize).ifPresent(paths::add);
            }
        }
        return paths;
    }

    private boolean coversGroup(String group, QueryFocus queryFocus, EvidenceFragment fragment) {
        String content = normalize(fragment.content());
        String path = normalize(fragment.path());
        boolean focusRelevant = hasQueryFocusHit(path, content, queryFocus);
        boolean specificFocusRelevant = hasSpecificQueryFocusHit(path, content, queryFocus);
        boolean fileNameSpecificFocusRelevant = hasSpecificQueryFocusHit(fileName(path), "", queryFocus);
        return switch (group) {
            case "controller_entrypoint" -> isJavaPath(path)
                    && specificFocusRelevant
                    && containsAny(path + "\n" + content, "controller", "@restcontroller", "@controller", "@requestmapping")
                    && containsAny(content, "@getmapping", "@postmapping", "@putmapping", "@deletemapping", "responseentity", "apiresponse");
            case "application_service" -> isJavaPath(path)
                    && !isTestPath(path)
                    && fileNameSpecificFocusRelevant
                    && (!implementationTraceTarget(queryFocus) || !content.contains("interface "))
                    && hasImplementationRole(path, content, queryFocus)
                    && (containsAny(content, "public ", "context", "generate", "implementation", "return ")
                    || (queryMentionsSourceEvidenceLoop(queryFocus) && path.endsWith("/sourceevidenceloopprobe.java")));
            case "key_model_result" -> isJavaPath(path)
                    && !isTestPath(path)
                    && !containsAny(path, "controller", "service", "repository", "adapter", "planner")
                    && focusRelevant
                    && (fileNameSpecificFocusRelevant
                    || containsAny(path + "\n" + content, "result", "response", "dto", "model", "plan", "asset",
                    "neighborhood", "trace", "recorder", "provider"))
                    && containsAny(content, "record ", "class ", "interface ");
            case "test_or_contract" -> isTestPath(path)
                    && (!hasExplicitClassFocus(queryFocus) || pathMatchesExplicitClassOrTestCompanion(path, queryFocus))
                    && specificFocusRelevant
                    && containsAny(content, "@test", "assertthat", "mockmvc", "assert");
            case "schema" -> path.endsWith(".sql")
                    && containsAny(content, "create table", "create index", "alter table");
            case "repository_sql" -> content.contains("jdbctemplate")
                    && containsAny(path + "\n" + content, "repository", "jdbc", "mapper", "dao")
                    && containsAny(content, "insert into", "select ", "select * from", "update ", "delete from");
            case "entity_or_model" -> isJavaPath(path)
                    && !isTestPath(path)
                    && !path.contains("repository")
                    && (path.contains("/domain/") || path.contains("/model/") || containsAny(content, "record "))
                    && containsAny(path + "\n" + content, "entity", "model", "document", "chunk", "record")
                    && containsAny(content, "record ", "class ");
            case "migration_runner" -> isJavaPath(path)
                    && containsAny(path + "\n" + content, "migration", "schema", "runner")
                    && containsAny(content, "postconstruct", "migrate", "create table", "alter table", "jdbctemplate", "class ");
            case "review_application_service" -> isJavaPath(path)
                    && hasServiceRole(path, content)
                    && containsAny(path + "\n" + content, "review", "service")
                    && containsAny(content, "createreview", "review", "feedback", "postprocessor");
            case "feedback_memory_signal" -> isJavaPath(path)
                    && containsAny(path + "\n" + content, "feedback", "memory", "signal", "review")
                    && containsAny(content, "false_positive", "false positive", "feedback", "signal");
            case "postprocessor_filtering" -> isJavaPath(path)
                    && containsAny(path + "\n" + content, "postprocessor", "processor", "filter")
                    && containsAny(content, "downgrade", "filter", "noise", "feedback");
            case "review_issue_model" -> isJavaPath(path)
                    && !isTestPath(path)
                    && !path.contains("repository")
                    && (path.contains("/domain/") || containsAny(content, "record ", "enum "))
                    && containsAny(path + "\n" + content, "review", "issue", "signal", "type")
                    && containsAny(content, "record ", "class ", "enum ");
            case "test_or_smoke" -> isTestPath(path)
                    && containsAny(content, "@test", "assertthat", "false_positive", "feedback", "review");
            case "properties" -> (isJavaPath(path)
                    && (path.contains("/config/") || content.contains("configurationproperties"))
                    && containsAny(path + "\n" + content, "properties", "config")
                    && containsAny(content, "configurationproperties", "apikey", "api-key", "timeout", "provider", "model"))
                    || ((path.endsWith(".yml") || path.endsWith(".yaml") || path.endsWith(".properties"))
                    && containsAny(path, "application", "config", "settings")
                    && containsAny(content, "apikey", "api-key", "apikeyenv", "timeout", "provider", "model"));
            case "settings_controller" -> isJavaPath(path)
                    && containsAny(path + "\n" + content, "controller", "@restcontroller", "@controller")
                    && containsAny(content, "settings", "/api/settings", "@postmapping", "@putmapping", "apikey", "timeout");
            case "local_config_store" -> isJavaPath(path)
                    && containsAny(path + "\n" + content, "store", "config", "settings")
                    && containsAny(content, "save", "load", "read", "write", "api-key", "apikey");
            case "provider_client" -> isJavaPath(path)
                    && (path.contains("/adapters/llm/") || path.endsWith("client.java"))
                    && containsAny(path + "\n" + content, "client", "provider", "llm")
                    && containsAny(content, "timeout", "apikey", "api-key", "authorization", "model");
            case "test_endpoint" -> isJavaPath(path)
                    && containsAny(content, "testconnection", "connectioncheck", "/test", "llmclient.chat", "chat(");
            default -> !content.isBlank();
        };
    }

    private boolean hasQueryFocusHit(String path, String content, QueryFocus queryFocus) {
        List<String> terms = queryFocusTerms(queryFocus);
        if (terms.isEmpty()) {
            return true;
        }
        return containsNormalizedOrCompact(path + "\n" + content, terms);
    }

    private boolean hasSpecificQueryFocusHit(String path, String content, QueryFocus queryFocus) {
        List<String> terms = querySpecificFocusTerms(queryFocus);
        if (terms.isEmpty()) {
            return hasQueryFocusHit(path, content, queryFocus);
        }
        return containsNormalizedOrCompact(path + "\n" + content, terms);
    }

    private boolean hasExplicitClassFocus(QueryFocus queryFocus) {
        if (queryFocus == null) {
            return false;
        }
        return queryFocus.rawTokens().stream().anyMatch(this::looksLikeExplicitClassName);
    }

    private boolean pathMatchesExplicitClassOrTestCompanion(String path, QueryFocus queryFocus) {
        if (queryFocus == null) {
            return false;
        }
        String compactFile = compact(fileName(path));
        for (String token : queryFocus.rawTokens()) {
            if (!looksLikeExplicitClassName(token)) {
                continue;
            }
            String compactToken = compact(token);
            if (compactFile.contains(compactToken)
                    || compactFile.contains(compactToken + "test")
                    || compactFile.contains(compactToken + "tests")) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeExplicitClassName(String token) {
        return token != null
                && token.length() > 3
                && Character.isUpperCase(token.charAt(0))
                && !token.equals(token.toUpperCase(Locale.ROOT));
    }

    private boolean containsNormalizedOrCompact(String haystack, List<String> terms) {
        String normalizedHaystack = normalize(haystack);
        String compactHaystack = compact(haystack);
        for (String term : terms) {
            String normalizedTerm = normalize(term);
            String compactTerm = compact(term);
            if (!normalizedTerm.isBlank() && normalizedHaystack.contains(normalizedTerm)) {
                return true;
            }
            if (!compactTerm.isBlank() && compactTerm.length() >= 3 && compactHaystack.contains(compactTerm)) {
                return true;
            }
        }
        return false;
    }

    private List<String> queryFocusTerms(QueryFocus queryFocus) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (queryFocus == null) {
            return List.of();
        }
        queryFocus.fileOrPathFragments().forEach(term -> addDiscoveryTerm(terms, term));
        queryFocus.camelCaseNames().forEach(term -> addDiscoveryTerm(terms, term));
        queryFocus.simpleNames().forEach(term -> addDiscoveryTerm(terms, term));
        queryFocus.englishKeywords().forEach(term -> addDiscoveryTerm(terms, term));
        queryFocus.chineseKeywords().forEach(term -> addDiscoveryTerm(terms, term));
        queryFocus.domainIntents().forEach(term -> addDiscoveryTerm(terms, term));
        addChineseAliases(queryFocus, terms);
        return terms.stream().toList();
    }

    private List<String> querySpecificFocusTerms(QueryFocus queryFocus) {
        if (queryFocus == null) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String fragment : queryFocus.fileOrPathFragments()) {
            String normalized = normalize(fragment);
            if (isSpecificFocusKeyword(normalized) || compact(normalized).length() >= 5) {
                addDiscoveryTerm(terms, normalized);
            }
        }
        for (String camelName : queryFocus.camelCaseNames()) {
            String normalized = normalize(camelName);
            if (isSpecificFocusKeyword(normalized) || compact(normalized).length() >= 5) {
                addDiscoveryTerm(terms, normalized);
            }
            for (String part : splitCamelParts(camelName)) {
                if (isSpecificFocusKeyword(part)) {
                    addDiscoveryTerm(terms, part);
                }
            }
        }
        for (String keyword : queryFocus.englishKeywords()) {
            String normalized = normalize(keyword);
            if (isSpecificFocusKeyword(normalized)) {
                addDiscoveryTerm(terms, normalized);
            }
        }
        for (String simpleName : queryFocus.simpleNames()) {
            String normalized = normalize(simpleName);
            if (isSpecificFocusKeyword(normalized)) {
                addDiscoveryTerm(terms, normalized);
            }
        }
        return terms.stream().toList();
    }

    private boolean isSpecificFocusKeyword(String keyword) {
        return keyword.length() >= 3 && !Set.of(
                "api",
                "config",
                "configuration",
                "context",
                "database",
                "endpoint",
                "feedback",
                "generate",
                "generation",
                "implemented",
                "implementation",
                "jdbc",
                "key",
                "llm",
                "model",
                "persistence",
                "provider",
                "repository",
                "review",
                "schema",
                "service",
                "settings",
                "sql",
                "test",
                "timeout"
        ).contains(keyword);
    }

    private void addChineseAliases(QueryFocus queryFocus, Set<String> terms) {
        if (queryFocus.chineseKeywords().contains("上下文")) {
            addDiscoveryTerm(terms, "context");
        }
        if (queryFocus.chineseKeywords().contains("生成")) {
            addDiscoveryTerm(terms, "generate");
            addDiscoveryTerm(terms, "generation");
        }
        if (queryFocus.chineseKeywords().contains("实现")) {
            addDiscoveryTerm(terms, "implementation");
            addDiscoveryTerm(terms, "service");
        }
        if (queryFocus.chineseKeywords().contains("数据库") || queryFocus.chineseKeywords().contains("持久")) {
            addDiscoveryTerm(terms, "database");
            addDiscoveryTerm(terms, "persistence");
        }
        if (queryFocus.chineseKeywords().contains("反馈")) {
            addDiscoveryTerm(terms, "feedback");
        }
        if (queryFocus.chineseKeywords().contains("配置")) {
            addDiscoveryTerm(terms, "settings");
            addDiscoveryTerm(terms, "config");
        }
        if (queryFocus.chineseKeywords().contains("测试")) {
            addDiscoveryTerm(terms, "test");
        }
    }

    private boolean isJavaPath(String path) {
        return normalize(path).endsWith(".java");
    }

    private boolean hasServiceRole(String path, String content) {
        String normalizedPath = normalize(path);
        return normalizedPath.endsWith("service.java")
                || normalizedPath.contains("/service/")
                || content.contains("@service");
    }

    private boolean hasImplementationRole(String path, String content, QueryFocus queryFocus) {
        String normalizedPath = normalize(path);
        return hasServiceRole(normalizedPath, content)
                || (normalizedPath.contains("/application/")
                && containsAny(normalizedPath, "adapter", "planner", "probe", "provider", "processor", "recorder", "store"))
                || (queryMentionsSourceEvidenceLoop(queryFocus)
                && normalizedPath.endsWith("/sourceevidenceloopprobe.java"));
    }

    private LinkedHashMap<String, String> initialGroupStatus(EvidenceContract contract) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String group : contract.requiredEvidenceGroups()) {
            result.put(group, MISSING);
        }
        return result;
    }

    private List<String> missingGroups(Map<String, String> groupStatus) {
        return groupStatus.entrySet().stream()
                .filter(entry -> !COVERED.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private int findMarkerLine(List<String> lines, List<String> markers, String symbol) {
        List<String> searchTerms = new ArrayList<>(markers == null ? List.of() : markers);
        if (symbol != null && !symbol.isBlank()) {
            searchTerms.add(symbol);
            int dot = symbol.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < symbol.length()) {
                searchTerms.add(symbol.substring(dot + 1));
            }
        }
        for (String marker : searchTerms) {
            String normalizedMarker = normalize(marker);
            if (normalizedMarker.isBlank()) {
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                if (normalize(lines.get(i)).contains(normalizedMarker)) {
                    return i;
                }
            }
        }
        return 0;
    }

    private LineRange lineRangeFor(String relativePath, String evidenceGroup, List<String> lines, int markerLine) {
        String lowerPath = relativePath.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".sql")) {
            return sqlRange(lowerPath, lines, markerLine);
        }
        if (lowerPath.endsWith(".yml")
                || lowerPath.endsWith(".yaml")
                || lowerPath.endsWith(".properties")
                || lowerPath.endsWith(".json")) {
            return boundedRange(lines.size(), markerLine - 6, markerLine + 24);
        }
        if (lowerPath.endsWith(".java")) {
            return javaRange(lines, markerLine, evidenceGroup);
        }
        return boundedRange(lines.size(), markerLine - 10, markerLine + 40);
    }

    private LineRange sqlRange(String lowerPath, List<String> lines, int markerLine) {
        if (lowerPath.endsWith("schema.sql")) {
            return boundedRange(lines.size(), 0, Math.min(lines.size() - 1, 120));
        }
        int start = markerLine;
        while (start > 0 && !normalize(lines.get(start)).contains("create table")
                && !normalize(lines.get(start)).contains("create index")
                && !normalize(lines.get(start)).contains("insert into")
                && !normalize(lines.get(start)).contains("select")
                && !normalize(lines.get(start)).contains("update")) {
            start--;
        }
        int end = markerLine;
        while (end < lines.size() - 1 && end - start < 80) {
            String line = lines.get(end).trim();
            if (line.endsWith(";") || line.equals(");")) {
                break;
            }
            end++;
        }
        return boundedRange(lines.size(), start, end);
    }

    private LineRange javaRange(List<String> lines, int markerLine, String evidenceGroup) {
        if (lines.size() <= 80) {
            return new LineRange(1, lines.size());
        }

        int annotationStart = markerLine;
        while (annotationStart > 0 && lines.get(annotationStart - 1).trim().startsWith("@")) {
            annotationStart--;
        }
        int methodStart;
        if (lines.get(markerLine).trim().startsWith("@")) {
            methodStart = findNextMethodStart(lines, markerLine);
            if (methodStart >= 0) {
                int start = shouldIncludeClassDeclaration(evidenceGroup)
                        ? expandToNearbyClassStart(lines, annotationStart)
                        : annotationStart;
                return methodRange(lines, start, methodStart);
            }
        }
        methodStart = findEnclosingMethodStart(lines, markerLine);
        if (methodStart >= 0) {
            int start = Math.min(annotationStart, methodStart);
            if (shouldIncludeClassDeclaration(evidenceGroup)) {
                start = expandToNearbyClassStart(lines, start);
            }
            return methodRange(lines, start, methodStart);
        }
        return boundedRange(lines.size(), markerLine - 10, markerLine + 70);
    }

    private boolean shouldIncludeClassDeclaration(String evidenceGroup) {
        return "migration_runner".equals(evidenceGroup);
    }

    private int expandToNearbyClassStart(List<String> lines, int start) {
        int lowerBound = Math.max(0, start - 40);
        for (int i = start; i >= lowerBound; i--) {
            String line = normalize(lines.get(i));
            if (line.contains(" class ")
                    || line.startsWith("class ")
                    || line.contains(" record ")
                    || line.startsWith("record ")
                    || line.contains(" interface ")
                    || line.startsWith("interface ")
                    || line.contains(" enum ")
                    || line.startsWith("enum ")) {
                int declarationStart = i;
                while (declarationStart > 0 && lines.get(declarationStart - 1).trim().startsWith("@")) {
                    declarationStart--;
                }
                return declarationStart;
            }
        }
        return start;
    }

    private LineRange methodRange(List<String> lines, int start, int methodStart) {
        int braceLine = findFirstBraceLine(lines, methodStart);
        if (braceLine < 0) {
            return boundedRange(lines.size(), start, methodStart + 40);
        }
        int end = findClosingBraceLine(lines, braceLine);
        return boundedRange(lines.size(), start, Math.min(end, start + MAX_FRAGMENT_LINES - 1));
    }

    private int findNextMethodStart(List<String> lines, int from) {
        for (int i = from; i < Math.min(lines.size(), from + 12); i++) {
            if (isMethodLike(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private int findEnclosingMethodStart(List<String> lines, int from) {
        for (int i = from; i >= 0; i--) {
            if (isMethodLike(lines.get(i)) && findFirstBraceLine(lines, i) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private boolean isMethodLike(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("if ")
                || trimmed.startsWith("if(")
                || trimmed.startsWith("for ")
                || trimmed.startsWith("for(")
                || trimmed.startsWith("while ")
                || trimmed.startsWith("while(")
                || trimmed.startsWith("switch ")
                || trimmed.startsWith("catch ")
                || trimmed.startsWith("return ")
                || trimmed.startsWith("new ")) {
            return false;
        }
        return METHOD_LIKE_PATTERN.matcher(line).matches()
                && !trimmed.endsWith(";")
                && trimmed.contains("(");
    }

    private int findFirstBraceLine(List<String> lines, int from) {
        for (int i = from; i < Math.min(lines.size(), from + 8); i++) {
            if (lines.get(i).contains("{")) {
                return i;
            }
        }
        return -1;
    }

    private int findClosingBraceLine(List<String> lines, int braceLine) {
        int depth = 0;
        boolean started = false;
        for (int i = braceLine; i < lines.size(); i++) {
            String line = lines.get(i);
            for (int j = 0; j < line.length(); j++) {
                char current = line.charAt(j);
                if (current == '{') {
                    depth++;
                    started = true;
                } else if (current == '}') {
                    depth--;
                    if (started && depth == 0) {
                        return i;
                    }
                }
            }
        }
        return Math.min(lines.size() - 1, braceLine + 60);
    }

    private LineRange boundedRange(int lineCount, int zeroBasedStart, int zeroBasedEnd) {
        int start = Math.max(0, zeroBasedStart);
        int end = Math.min(lineCount - 1, Math.max(start, zeroBasedEnd));
        if (end - start + 1 > MAX_FRAGMENT_LINES) {
            end = start + MAX_FRAGMENT_LINES - 1;
        }
        return new LineRange(start + 1, end + 1);
    }

    private String renderLines(List<String> lines, LineRange range, int maxCharacters) {
        StringBuilder builder = new StringBuilder();
        int startIndex = Math.max(0, range.start() - 1);
        int endIndex = Math.min(lines.size() - 1, range.end() - 1);
        for (int i = startIndex; i <= endIndex; i++) {
            String line = lines.get(i);
            String rendered = (i + 1) + ": " + line + System.lineSeparator();
            if (builder.length() + rendered.length() > maxCharacters) {
                break;
            }
            builder.append(rendered);
        }
        return builder.toString();
    }

    private Optional<Path> resolveEvidencePath(Path root, String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank() || requestedPath.contains("..")) {
            return Optional.empty();
        }
        try {
            Path resolved = root.resolve(requestedPath).toAbsolutePath().normalize();
            if (!resolved.startsWith(root) || !Files.isRegularFile(resolved) || !isAllowedEvidencePath(root, resolved)) {
                return Optional.empty();
            }
            return Optional.of(resolved);
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private Path resolveProjectRoot(Path projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot is required");
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("projectRoot must be an existing directory");
        }
        return root;
    }

    private List<String> blockedLegacySources(Path root) {
        return blockedLegacySourcesCache.computeIfAbsent(root, this::blockedLegacySourcesUncached);
    }

    private List<String> blockedLegacySourcesUncached(Path root) {
        return regularFiles(root, MAX_BLOCKED_LEGACY_SOURCES * 20).stream()
                    .map(path -> relativePath(root, path))
                    .filter(this::isBlockedLegacySource)
                    .sorted()
                    .limit(MAX_BLOCKED_LEGACY_SOURCES)
                    .toList();
    }

    private boolean isAllowedEvidencePath(Path root, Path file) {
        String relative = relativePath(root, file);
        return isAllowedEvidenceRelativePath(relative)
                && BLOCKED_DIRECTORY_PREFIXES.stream().noneMatch(prefix -> relative.toLowerCase(Locale.ROOT).startsWith(prefix))
                && !hasBlockedPathSegment(relative);
    }

    private boolean isAllowedEvidenceRelativePath(String relative) {
        return !isBlockedLegacySource(relative)
                && BLOCKED_DIRECTORY_PREFIXES.stream().noneMatch(prefix -> relative.toLowerCase(Locale.ROOT).startsWith(prefix))
                && !hasBlockedPathSegment(relative);
    }

    private boolean isBlockedLegacySource(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        String fileName = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
        if (BLOCKED_EXACT_FILES.stream().anyMatch(blocked -> blocked.equalsIgnoreCase(normalized))) {
            return true;
        }
        return lower.startsWith("docs/")
                || lower.startsWith(".ai/")
                || lower.startsWith(".ai/generated/")
                || lower.startsWith(".ai/manual/")
                || lower.startsWith("project_ai_docs/")
                || lower.equals(".ai/ai_readme.md")
                || lower.equals("readme.md")
                || lower.startsWith("readme.")
                || fileName.contains("readme")
                || lower.endsWith("/sourceevidencelooprealllmacceptance.java")
                || lower.endsWith("/sourcegroundedknowledgeragrealllmacceptance.java")
                || lower.endsWith("/realllmmanualacceptance.java");
    }

    private boolean isSearchable(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        return SEARCHABLE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private List<Path> allowedEvidenceFiles(Path root, int limit) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && isBlockedTraversalDirectory(root, dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return files.size() >= limit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()
                            && isAllowedEvidencePath(root, file)
                            && isSearchable(relativePath(root, file))) {
                        files.add(file);
                    }
                    return files.size() >= limit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            return List.of();
        }
        files.sort(Comparator.comparing(path -> relativePath(root, path)));
        return files;
    }

    private List<Path> regularFiles(Path root, int limit) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && isBlockedTraversalDirectory(root, dir)
                            && !isBlockedLegacySource(relativePath(root, dir) + "/")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return files.size() >= limit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        files.add(file);
                    }
                    return files.size() >= limit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            return List.of();
        }
        files.sort(Comparator.comparing(path -> relativePath(root, path)));
        return files;
    }

    private boolean isBlockedTraversalDirectory(Path root, Path dir) {
        String relative = relativePath(root, dir);
        String lower = relative.toLowerCase(Locale.ROOT);
        String prefix = lower.endsWith("/") ? lower : lower + "/";
        return BLOCKED_DIRECTORY_PREFIXES.stream().anyMatch(prefix::startsWith)
                || prefix.startsWith("workspace/projects/")
                || hasBlockedPathSegment(prefix);
    }

    private boolean hasBlockedPathSegment(String relativePath) {
        String lower = "/" + normalize(relativePath) + "/";
        return lower.contains("/node_modules/")
                || lower.contains("/dist/")
                || lower.contains("/src/test/resources/context-benchmark/")
                || lower.contains("/build/")
                || lower.contains("/target/")
                || lower.contains("/out/")
                || lower.contains("/logs/");
    }

    private String relativePath(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private String candidateKey(Candidate candidate) {
        return candidate.path() + "|" + candidate.expectedEvidenceGroup();
    }

    private String fileName(String path) {
        String normalizedPath = normalize(path);
        int slash = normalizedPath.lastIndexOf('/');
        return slash >= 0 ? normalizedPath.substring(slash + 1) : normalizedPath;
    }

    private boolean containsAny(String text, String... needles) {
        return Arrays.stream(needles).filter(Objects::nonNull).anyMatch(text::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private String compact(String value) {
        return normalize(value).replaceAll("[^a-z0-9]+", "");
    }

    private String compactLowercaseForOutput(String value) {
        return normalize(value).replaceAll("[^\\p{IsHan}a-z0-9]+", "");
    }

    private boolean isTestPath(String normalizedPath) {
        String path = normalize(normalizedPath);
        return path.startsWith("src/test/") || path.contains("/src/test/");
    }

    public record ProbeRequest(
            Path projectRoot,
            String question,
            int maxIterations,
            int maxEvidenceTokens,
            String intentOverride
    ) {
        public ProbeRequest(Path projectRoot, String question, int maxIterations, int maxEvidenceTokens) {
            this(projectRoot, question, maxIterations, maxEvidenceTokens, "");
        }

        public ProbeRequest {
            intentOverride = intentOverride == null ? "" : intentOverride;
        }
    }

    public record ProbeResult(
            String intent,
            QueryFocus queryFocus,
            List<String> requiredEvidenceGroups,
            List<Iteration> iterations,
            List<Candidate> candidates,
            List<FileRead> filesRead,
            List<EvidenceFragment> evidencePack,
            Sufficiency sufficiency,
            List<String> blockedLegacySources
    ) {
        public ProbeResult {
            queryFocus = queryFocus == null ? QueryFocus.empty() : queryFocus;
            requiredEvidenceGroups = requiredEvidenceGroups == null ? List.of() : List.copyOf(requiredEvidenceGroups);
            iterations = iterations == null ? List.of() : List.copyOf(iterations);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            filesRead = filesRead == null ? List.of() : List.copyOf(filesRead);
            evidencePack = evidencePack == null ? List.of() : List.copyOf(evidencePack);
            blockedLegacySources = blockedLegacySources == null ? List.of() : List.copyOf(blockedLegacySources);
        }
    }

    public record QueryFocus(
            String originalQuestion,
            List<String> rawTokens,
            List<String> chineseKeywords,
            List<String> englishKeywords,
            String compactLowercase,
            List<String> camelCaseNames,
            List<String> simpleNames,
            List<String> fileOrPathFragments,
            List<String> domainIntents
    ) {
        public QueryFocus {
            originalQuestion = originalQuestion == null ? "" : originalQuestion;
            rawTokens = rawTokens == null ? List.of() : List.copyOf(rawTokens);
            chineseKeywords = chineseKeywords == null ? List.of() : List.copyOf(chineseKeywords);
            englishKeywords = englishKeywords == null ? List.of() : List.copyOf(englishKeywords);
            compactLowercase = compactLowercase == null ? "" : compactLowercase;
            camelCaseNames = camelCaseNames == null ? List.of() : List.copyOf(camelCaseNames);
            simpleNames = simpleNames == null ? List.of() : List.copyOf(simpleNames);
            fileOrPathFragments = fileOrPathFragments == null ? List.of() : List.copyOf(fileOrPathFragments);
            domainIntents = domainIntents == null ? List.of() : List.copyOf(domainIntents);
        }

        private static QueryFocus empty() {
            return new QueryFocus("", List.of(), List.of(), List.of(), "", List.of(), List.of(), List.of(), List.of());
        }
    }

    public record Iteration(
            int number,
            String reason,
            List<Candidate> candidates,
            Map<String, String> sufficiencyGroups
    ) {
        public Iteration {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            sufficiencyGroups = sufficiencyGroups == null ? Map.of() : Map.copyOf(sufficiencyGroups);
        }
    }

    public record Candidate(
            String path,
            String symbol,
            String matchedReason,
            String expectedEvidenceGroup,
            int rank
    ) {
    }

    public record FileRead(
            String path,
            int startLine,
            int endLine,
            String evidenceGroup
    ) {
    }

    public record EvidenceFragment(
            String path,
            int startLine,
            int endLine,
            String evidenceGroup,
            String symbolOrBlock,
            String content,
            String whySelected
    ) {
    }

    public record Sufficiency(
            String status,
            Map<String, String> groups,
            List<String> missingGroups
    ) {
        public Sufficiency {
            groups = groups == null ? Map.of() : Map.copyOf(groups);
            missingGroups = missingGroups == null ? List.of() : List.copyOf(missingGroups);
        }
    }

    private record EvidenceContract(
            String intent,
            List<EvidenceGroupSpec> groups
    ) {
        List<String> requiredEvidenceGroups() {
            return groups.stream().map(EvidenceGroupSpec::name).toList();
        }
    }

    private record EvidenceGroupSpec(
            String name,
            List<CandidateSpec> candidates
    ) {
    }

    private record CandidateSpec(
            String evidenceGroup,
            String symbol,
            String reason,
            List<String> markers,
            List<String> searchTerms
    ) {
        CandidateSpec {
            markers = markers == null ? List.of() : List.copyOf(markers);
            searchTerms = searchTerms == null ? List.of() : List.copyOf(searchTerms);
        }
    }

    private record CandidatePlan(
            Candidate candidate,
            List<String> markers,
            List<String> searchTerms
    ) {
        CandidatePlan {
            markers = markers == null ? List.of() : List.copyOf(markers);
            searchTerms = searchTerms == null ? List.of() : List.copyOf(searchTerms);
        }
    }

    private record LineRange(int start, int end) {
    }

    private record DiscoveredPath(String path, double score, String reason) {
    }

    private record DiscoveryCorpus(List<CorpusFile> files) {
        DiscoveryCorpus {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    private record CorpusFile(String relativePath, String content) {
    }
}
