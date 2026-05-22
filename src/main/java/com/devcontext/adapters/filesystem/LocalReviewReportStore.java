package com.devcontext.adapters.filesystem;

import com.devcontext.ports.review.ReviewReportStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class LocalReviewReportStore implements ReviewReportStore {

    @Override
    public String writeReport(String rootPath, Long reviewId, String markdown) {
        String relativePath = ".ai/reviews/review-" + reviewId + ".md";
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        Path target = root.resolve(relativePath).toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Review report path escapes project root");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, markdown);
            return relativePath;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write review report: " + relativePath, e);
        }
    }
}
