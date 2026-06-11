package com.devcontext.application.review;

import com.devcontext.domain.review.ReviewIssue;
import com.devcontext.domain.review.ReviewMemorySignal;
import com.devcontext.domain.review.ReviewMemorySignalType;
import com.devcontext.ports.review.ReviewIssueRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class ReviewMemorySignalService {

    private final ReviewIssueRepository reviewIssueRepository;

    public ReviewMemorySignalService(ReviewIssueRepository reviewIssueRepository) {
        this.reviewIssueRepository = reviewIssueRepository;
    }

    public List<ReviewMemorySignal> findProjectSignals(Long projectId, int limit) {
        return reviewIssueRepository.findRecentFeedbackByProjectId(projectId, limit).stream()
                .map(issue -> toSignal(projectId, issue))
                .flatMap(List::stream)
                .toList();
    }

    private List<ReviewMemorySignal> toSignal(Long projectId, ReviewIssue issue) {
        ReviewMemorySignalType type = signalType(issue.status());
        if (type == null) {
            return List.of();
        }
        return List.of(new ReviewMemorySignal(
                projectId,
                issue.reviewId(),
                issue.id(),
                type,
                issue.status(),
                issue.title(),
                issue.filePath(),
                issue.lineNumber(),
                issue.description(),
                issue.impact(),
                issue.suggestion(),
                issue.note(),
                issue.updatedAt()
        ));
    }

    private ReviewMemorySignalType signalType(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "accepted", "fixed" -> ReviewMemorySignalType.CONFIRMED_ISSUE_PATTERN;
            case "false_positive", "rejected" -> ReviewMemorySignalType.FALSE_POSITIVE_PATTERN;
            default -> null;
        };
    }
}
