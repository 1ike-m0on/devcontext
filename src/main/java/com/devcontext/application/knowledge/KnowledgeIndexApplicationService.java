package com.devcontext.application.knowledge;

import com.devcontext.common.error.ApiException;
import com.devcontext.domain.knowledge.EmbeddingVector;
import com.devcontext.domain.knowledge.KnowledgeChunk;
import com.devcontext.domain.knowledge.KnowledgeDocument;
import com.devcontext.domain.knowledge.KnowledgeIndexResult;
import com.devcontext.domain.knowledge.KnowledgeSource;
import com.devcontext.domain.knowledge.VectorDocument;
import com.devcontext.ports.knowledge.EmbeddingClient;
import com.devcontext.ports.knowledge.KnowledgeChunkRepository;
import com.devcontext.ports.knowledge.KnowledgeDocumentRepository;
import com.devcontext.ports.knowledge.KnowledgeSourceRepository;
import com.devcontext.ports.knowledge.VectorStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIndexApplicationService {

    public static final String VECTOR_COLLECTION = "knowledge_chunk";
    private static final Set<String> VALID_SOURCE_TYPES = Set.of("markdown_dir", "project_ai_docs");
    private static final long MAX_INDEX_FILE_BYTES = 180_000;
    private static final Set<String> IGNORED_PATH_PREFIXES = Set.of(
            ".git/",
            ".gradle/",
            ".idea/",
            ".vscode/",
            "build/",
            "coverage/",
            "data/",
            "dist/",
            "logs/",
            "node_modules/",
            "out/",
            "target/"
    );
    private static final Set<String> AI_DOC_EXTENSIONS = Set.of(".md", ".txt", ".json");
    private static final Set<String> PROJECT_DOC_EXTENSIONS = Set.of(".md", ".txt");
    private static final Set<String> PROJECT_RESOURCE_EXTENSIONS = Set.of(".sql", ".xml", ".lua", ".yml", ".yaml", ".properties");
    private static final Set<String> PROJECT_CODE_EXTENSIONS = Set.of(".java", ".kt", ".kts", ".ts", ".tsx", ".js", ".jsx");
    private static final Set<String> PROJECT_ROOT_DOC_NAMES = Set.of(
            "benchmark.md",
            "deployment.md",
            "docker.md",
            "observability.md",
            "readme.md"
    );
    private static final Set<String> PROJECT_CODE_KEYWORDS = Set.of(
            "actuator",
            "controller",
            "dao",
            "database",
            "entity",
            "index",
            "mapper",
            "metrics",
            "micrometer",
            "migration",
            "monitor",
            "observability",
            "repository",
            "schema",
            "service",
            "table"
    );

    private final KnowledgeSourceRepository sourceRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final MarkdownChunker chunker;
    private final KnowledgeCoverageService coverageService;

    public KnowledgeIndexApplicationService(
            KnowledgeSourceRepository sourceRepository,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeChunkRepository chunkRepository,
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            MarkdownChunker chunker,
            KnowledgeCoverageService coverageService
    ) {
        this.sourceRepository = sourceRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.chunker = chunker;
        this.coverageService = coverageService;
    }

    public KnowledgeSource createSource(CreateKnowledgeSourceCommand command) {
        requireText(command.name(), "name");
        requireText(command.rootPath(), "rootPath");
        String sourceType = normalizeSourceType(command.sourceType());
        Path root = Path.of(command.rootPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new ApiException("KNOWLEDGE_SOURCE_PATH_INVALID", "Knowledge source path is not a directory", HttpStatus.BAD_REQUEST);
        }
        Instant now = Instant.now();
        return sourceRepository.save(new KnowledgeSource(
                null,
                command.name().trim(),
                root.toString(),
                sourceType,
                "created",
                now,
                now
        ));
    }

    public List<KnowledgeSource> listSources() {
        return sourceRepository.findAll();
    }

    public KnowledgeIndexResult indexSource(Long sourceId) {
        KnowledgeSource source = getSource(sourceId);
        Path root = Path.of(source.rootPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new ApiException("KNOWLEDGE_SOURCE_PATH_INVALID", "Knowledge source path is not a directory", HttpStatus.BAD_REQUEST);
        }

        chunkRepository.deleteBySourceId(sourceId);
        documentRepository.deleteBySourceId(sourceId);
        vectorStore.deleteBySourceId(VECTOR_COLLECTION, sourceId);

        List<Path> files = scanFiles(root, source.sourceType());
        int documentsIndexed = 0;
        int chunksIndexed = 0;
        Instant now = Instant.now();
        for (Path file : files) {
            String content = readFile(file);
            if (content.isBlank()) {
                continue;
            }
            String relativePath = normalizePath(root.relativize(file));
            String indexableContent = indexableContent(relativePath, content);
            KnowledgeDocument document = documentRepository.save(new KnowledgeDocument(
                    null,
                    sourceId,
                    relativePath,
                    titleOf(file, indexableContent),
                    sha256(indexableContent),
                    "indexed",
                    now,
                    now,
                    now
            ));
            documentsIndexed++;
            List<MarkdownChunker.MarkdownChunk> chunks = chunker.chunk(indexableContent);
            for (int i = 0; i < chunks.size(); i++) {
                MarkdownChunker.MarkdownChunk chunk = chunks.get(i);
                String vectorId = vectorId(sourceId, relativePath, i, chunk.content());
                KnowledgeChunk saved = chunkRepository.save(new KnowledgeChunk(
                        null,
                        sourceId,
                        document.id(),
                        i,
                        chunk.headingPath(),
                        chunk.content(),
                        sha256(chunk.content()),
                        estimateTokens(chunk.content()),
                        vectorId,
                        now
                ));
                EmbeddingVector embedding = embeddingClient.embed(chunk.headingPath() + "\n" + chunk.content());
                vectorStore.upsert(new VectorDocument(
                        vectorId,
                        VECTOR_COLLECTION,
                        sourceId,
                        embedding,
                        Map.<String, Object>of(
                                "chunkId", String.valueOf(saved.id()),
                                "documentId", String.valueOf(document.id()),
                                "filePath", relativePath
                        )
                ));
                chunksIndexed++;
            }
        }
        sourceRepository.update(new KnowledgeSource(
                source.id(),
                source.name(),
                source.rootPath(),
                source.sourceType(),
                "indexed",
                source.createdAt(),
                Instant.now()
        ));
        return new KnowledgeIndexResult(sourceId, documentsIndexed, chunksIndexed, coverageService.buildReport(sourceId));
    }

    public com.devcontext.domain.knowledge.EvidenceCoverageReport coverage(Long sourceId) {
        getSource(sourceId);
        return coverageService.buildReport(sourceId);
    }

    public KnowledgeSource getSource(Long sourceId) {
        return sourceRepository.findById(sourceId)
                .orElseThrow(() -> new ApiException("KNOWLEDGE_SOURCE_NOT_FOUND", "Knowledge source not found", HttpStatus.NOT_FOUND));
    }

    private List<Path> scanFiles(Path root, String sourceType) {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isAllowedPath(root, path, sourceType))
                    .sorted(Comparator.comparing(path -> normalizePath(root.relativize(path))))
                    .toList();
        } catch (IOException e) {
            throw new ApiException("KNOWLEDGE_INDEX_FAILED", "Failed to scan knowledge source: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    private boolean isAllowedPath(Path root, Path path, String sourceType) {
        String relative = normalizePath(root.relativize(path));
        String lower = relative.toLowerCase(Locale.ROOT);
        if ("project_ai_docs".equals(sourceType)) {
            if (isAiDoc(relative, lower)) {
                return hasExtension(lower, AI_DOC_EXTENSIONS);
            }
            if (isIgnored(relative)) {
                return false;
            }
            return isProjectEvidencePath(lower);
        }
        if (isIgnored(relative)) {
            return false;
        }
        return hasExtension(lower, PROJECT_DOC_EXTENSIONS);
    }

    private String readFile(Path file) {
        try {
            if (Files.size(file) > MAX_INDEX_FILE_BYTES) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException("KNOWLEDGE_INDEX_FAILED", "Failed to read knowledge document: " + file, HttpStatus.BAD_REQUEST);
        }
    }

    private boolean isAiDoc(String relative, String lower) {
        return relative.equals("AGENTS.md") || lower.equals("agents.md") || lower.startsWith(".ai/");
    }

    private boolean isIgnored(String relative) {
        String lower = relative.toLowerCase(Locale.ROOT);
        return IGNORED_PATH_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    private boolean isProjectEvidencePath(String lower) {
        if (hasExtension(lower, PROJECT_DOC_EXTENSIONS)) {
            return lower.startsWith("docs/")
                    || lower.startsWith("deploy/")
                    || PROJECT_ROOT_DOC_NAMES.contains(fileName(lower))
                    || lower.endsWith("/readme.md");
        }
        if (lower.endsWith(".json")) {
            return isObservabilityPath(lower);
        }
        if (isComposeFile(lower)) {
            return true;
        }
        if (lower.endsWith(".sql")) {
            return true;
        }
        if (lower.endsWith(".lua")) {
            return lower.startsWith("src/main/resources/")
                    || lower.startsWith("scripts/")
                    || lower.contains("/lua/")
                    || lower.contains("/script/")
                    || lower.contains("/scripts/");
        }
        if (lower.endsWith(".xml")) {
            return lower.startsWith("src/main/resources/")
                    || lower.contains("/mapper/")
                    || lower.contains("/mybatis/")
                    || lower.contains("/database/");
        }
        if (hasExtension(lower, PROJECT_RESOURCE_EXTENSIONS)) {
            return lower.startsWith("src/main/resources/")
                    || lower.startsWith("config/")
                    || lower.startsWith("deploy/")
                    || lower.contains("/db/")
                    || lower.contains("/migration/")
                    || lower.contains("/migrations/")
                    || lower.contains("/monitoring/")
                    || lower.contains("/schema/");
        }
        if (hasExtension(lower, PROJECT_CODE_EXTENSIONS)) {
            if (isProjectTestPath(lower)) {
                return true;
            }
            return PROJECT_CODE_KEYWORDS.stream().anyMatch(lower::contains);
        }
        return false;
    }

    private boolean isProjectTestPath(String lower) {
        return lower.startsWith("src/test/")
                || lower.contains("/src/test/")
                || lower.contains("/test/")
                || lower.contains("/tests/")
                || lower.endsWith("test.java")
                || lower.endsWith("tests.java");
    }

    private boolean isComposeFile(String lower) {
        return lower.equals("compose.yml")
                || lower.equals("compose.yaml")
                || lower.equals("docker-compose.yml")
                || lower.equals("docker-compose.yaml")
                || lower.endsWith("/compose.yml")
                || lower.endsWith("/compose.yaml")
                || lower.endsWith("/docker-compose.yml")
                || lower.endsWith("/docker-compose.yaml");
    }

    private boolean isObservabilityPath(String lower) {
        return lower.contains("monitoring")
                || lower.contains("prometheus")
                || lower.contains("grafana")
                || lower.contains("metrics")
                || lower.contains("micrometer")
                || lower.contains("actuator")
                || lower.contains("observability");
    }

    private String fileName(String lower) {
        int slash = lower.lastIndexOf('/');
        return slash >= 0 ? lower.substring(slash + 1) : lower;
    }

    private boolean hasExtension(String lower, Set<String> extensions) {
        return extensions.stream().anyMatch(lower::endsWith);
    }

    private String indexableContent(String relativePath, String content) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        if (hasExtension(lower, PROJECT_DOC_EXTENSIONS)) {
            return content;
        }
        return "# " + relativePath + "\n\n"
                + "File: " + relativePath + "\n"
                + "Type: " + sourceKind(lower) + "\n\n"
                + "```" + codeFenceLanguage(lower) + "\n"
                + content.trim() + "\n"
                + "```\n";
    }

    private String sourceKind(String lower) {
        if (lower.endsWith(".sql")) {
            return "SQL database schema or migration";
        }
        if (lower.endsWith(".xml")) {
            return "XML mapper or configuration";
        }
        if (lower.endsWith(".lua")) {
            return "Lua script or Redis script";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties")) {
            return "application configuration";
        }
        if (lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".kts")) {
            return "backend source code";
        }
        if (lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".js") || lower.endsWith(".jsx")) {
            return "frontend source code";
        }
        if (lower.endsWith(".json")) {
            return "structured project metadata";
        }
        return "project evidence";
    }

    private String codeFenceLanguage(String lower) {
        if (lower.endsWith(".sql")) {
            return "sql";
        }
        if (lower.endsWith(".xml")) {
            return "xml";
        }
        if (lower.endsWith(".lua")) {
            return "lua";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return "yaml";
        }
        if (lower.endsWith(".properties")) {
            return "properties";
        }
        if (lower.endsWith(".java")) {
            return "java";
        }
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) {
            return "kotlin";
        }
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            return "typescript";
        }
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) {
            return "javascript";
        }
        if (lower.endsWith(".json")) {
            return "json";
        }
        return "";
    }

    private String titleOf(Path file, String content) {
        return content.lines()
                .filter(line -> line.startsWith("#"))
                .map(line -> line.replaceFirst("^#+", "").trim())
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse(file.getFileName().toString());
    }

    private String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String normalizeSourceType(String sourceType) {
        String normalized = sourceType == null || sourceType.isBlank()
                ? "markdown_dir"
                : sourceType.trim().toLowerCase(Locale.ROOT);
        if (!VALID_SOURCE_TYPES.contains(normalized)) {
            throw new ApiException("KNOWLEDGE_SOURCE_TYPE_INVALID", "Invalid knowledge source type", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ApiException("KNOWLEDGE_FIELD_REQUIRED", fieldName + " is required", HttpStatus.BAD_REQUEST);
        }
    }

    private int estimateTokens(String text) {
        return text == null || text.isBlank() ? 0 : Math.max(1, text.length() / 4);
    }

    private String vectorId(Long sourceId, String relativePath, int chunkIndex, String content) {
        return "knowledge:" + sourceId + ":" + chunkIndex + ":" + sha256(relativePath + "\n" + content).substring(0, 16);
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
