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

    private final KnowledgeSourceRepository sourceRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final MarkdownChunker chunker;

    public KnowledgeIndexApplicationService(
            KnowledgeSourceRepository sourceRepository,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeChunkRepository chunkRepository,
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            MarkdownChunker chunker
    ) {
        this.sourceRepository = sourceRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.chunker = chunker;
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
            KnowledgeDocument document = documentRepository.save(new KnowledgeDocument(
                    null,
                    sourceId,
                    relativePath,
                    titleOf(file, content),
                    sha256(content),
                    "indexed",
                    now,
                    now,
                    now
            ));
            documentsIndexed++;
            List<MarkdownChunker.MarkdownChunk> chunks = chunker.chunk(content);
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
                        Map.of(
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
        return new KnowledgeIndexResult(sourceId, documentsIndexed, chunksIndexed);
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
        if (relative.startsWith(".git/") || relative.startsWith("target/")
                || relative.startsWith("data/") || relative.startsWith("logs/")) {
            return false;
        }
        String lower = relative.toLowerCase(Locale.ROOT);
        if ("project_ai_docs".equals(sourceType)) {
            boolean isAiDoc = relative.equals("AGENTS.md") || relative.startsWith(".ai/");
            return isAiDoc && (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".json"));
        }
        return lower.endsWith(".md") || lower.endsWith(".txt");
    }

    private String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException("KNOWLEDGE_INDEX_FAILED", "Failed to read knowledge document: " + file, HttpStatus.BAD_REQUEST);
        }
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
