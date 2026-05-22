package com.devcontext.adapters.filesystem;

import com.devcontext.application.review.context.ReviewContextProvider;
import com.devcontext.application.review.context.ReviewContextRequest;
import com.devcontext.domain.context.ContextItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProjectFileReviewContextProvider implements ReviewContextProvider {

    private static final List<ContextFile> FILES = List.of(
            new ContextFile("PROJECT_AGENTS", "AGENTS.md", 900),
            new ContextFile("PROJECT_AI_README", ".ai/AI_README.md", 850),
            new ContextFile("PROJECT_CODE_MAP", ".ai/code-map.json", 820),
            new ContextFile("PROJECT_TECH_ARCHITECTURE", ".ai/generated/tech-architecture.md", 780),
            new ContextFile("CODING_PREFERENCES", ".ai/manual/coding-preferences.md", 760),
            new ContextFile("PITFALLS", ".ai/manual/pitfalls.md", 740),
            new ContextFile("DECISIONS", ".ai/manual/decisions.md", 720)
    );

    @Override
    public boolean supports(ReviewContextRequest request) {
        return request.project() != null;
    }

    @Override
    public List<ContextItem> provide(ReviewContextRequest request) {
        Path root = Path.of(request.project().rootPath()).toAbsolutePath().normalize();
        List<ContextItem> items = new ArrayList<>();
        for (ContextFile file : FILES) {
            Path target = root.resolve(file.path()).toAbsolutePath().normalize();
            if (!target.startsWith(root) || !Files.exists(target) || !Files.isRegularFile(target)) {
                continue;
            }
            readSmallFile(target).ifPresent(content -> items.add(new ContextItem(
                    null,
                    null,
                    request.project().id(),
                    file.type(),
                    file.path(),
                    content,
                    file.path(),
                    file.priority(),
                    estimateTokens(content),
                    sha256(content),
                    Instant.now()
            )));
        }
        return items;
    }

    private java.util.Optional<String> readSmallFile(Path path) {
        try {
            if (Files.size(path) > 100_000) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(Files.readString(path));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
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

    private record ContextFile(String type, String path, int priority) {
    }
}
