package com.devcontext.ports.review;

import com.devcontext.domain.review.ReviewIssue;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReviewIssueRepository {

    ReviewIssue save(ReviewIssue issue);

    List<ReviewIssue> findByReviewId(Long reviewId);

    Optional<ReviewIssue> findById(Long issueId);

    List<ReviewIssue> findRecentFeedbackByProjectId(Long projectId, int limit);

    List<ReviewIssue> findRecentFeedbackByProjectIdBefore(Long projectId, Instant before, int limit);

    ReviewIssue updateStatus(Long issueId, String status, String note);
}
