package com.devcontext.ports.review;

import com.devcontext.domain.review.ReviewIssue;
import java.util.List;
import java.util.Optional;

public interface ReviewIssueRepository {

    ReviewIssue save(ReviewIssue issue);

    List<ReviewIssue> findByReviewId(Long reviewId);

    Optional<ReviewIssue> findById(Long issueId);

    ReviewIssue updateStatus(Long issueId, String status, String note);
}
