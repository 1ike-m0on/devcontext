package com.devcontext.application.review.context;

import com.devcontext.domain.context.ContextItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GitDiffReviewContextProvider implements ReviewContextProvider {

    @Override
    public boolean supports(ReviewContextRequest request) {
        return request.diff() != null && request.diff().text() != null;
    }

    @Override
    public List<ContextItem> provide(ReviewContextRequest request) {
        String content = """
                Changed files:
                %s

                Diff truncated by DevContext:
                %s

                Diff:
                %s
                """.formatted(
                String.join(System.lineSeparator(), request.diff().changedFiles()),
                request.diff().truncated() ? "yes" : "no",
                request.diff().text()
        );
        return List.of(new ContextItem(
                null,
                null,
                request.project().id(),
                "GIT_DIFF",
                "Git diff",
                content,
                "git:diff",
                1000,
                estimateTokens(content),
                sha256(content),
                Instant.now()
        ));
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
