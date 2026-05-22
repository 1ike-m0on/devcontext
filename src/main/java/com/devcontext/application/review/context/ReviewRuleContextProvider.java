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
public class ReviewRuleContextProvider implements ReviewContextProvider {

    private static final String RULES = """
            Review focus:
            1. Functional correctness and edge cases.
            2. Null safety and parameter validation.
            3. Exception handling and transaction boundaries.
            4. Data consistency and concurrency.
            5. Performance risks such as N+1 queries or repeated IO.
            6. Security risks such as secrets, unsafe input, or permission gaps.
            7. Maintainability, module boundaries, and test gaps.

            Severity:
            - critical: must fix before merge.
            - warning: should fix or explicitly accept.
            - info: optional improvement.

            Constraints:
            - Focus on this diff.
            - Do not invent issues without evidence.
            - Mark uncertain items as needing confirmation.
            - Do not rewrite large blocks of code.
            """;

    @Override
    public boolean supports(ReviewContextRequest request) {
        return true;
    }

    @Override
    public List<ContextItem> provide(ReviewContextRequest request) {
        return List.of(new ContextItem(
                null,
                null,
                request.project().id(),
                "REVIEW_RULES",
                "Review rules",
                RULES,
                "built-in:review-rules",
                700,
                estimateTokens(RULES),
                sha256(RULES),
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
