package com.devcontext.adapters.web;

import com.devcontext.application.review.CreateReviewCommand;
import com.devcontext.application.review.ReviewApplicationService;
import com.devcontext.common.api.ApiResponse;
import com.devcontext.domain.git.GitReviewSource;
import com.devcontext.domain.review.ReviewCreateResult;
import com.devcontext.domain.review.ReviewDetail;
import com.devcontext.domain.review.ReviewEventDetail;
import com.devcontext.domain.review.ReviewIssue;
import com.devcontext.domain.review.ReviewRecord;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReviewController {

    private final ReviewApplicationService reviewService;

    public ReviewController(ReviewApplicationService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/api/projects/{projectId}/reviews")
    public ApiResponse<ReviewCreateResult> createReview(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateReviewRequest request
    ) {
        return ApiResponse.ok(reviewService.createReview(projectId, new CreateReviewCommand(
                request.sourceType(),
                request.baseBranch(),
                request.compareBranch(),
                request.diffText(),
                request.mode(),
                request.selectedFiles()
        )));
    }

    @GetMapping("/api/projects/{projectId}/review-sources")
    public ApiResponse<List<GitReviewSource>> inspectReviewSources(@PathVariable Long projectId) {
        return ApiResponse.ok(reviewService.inspectReviewSources(projectId));
    }

    @GetMapping("/api/reviews/{reviewId}")
    public ApiResponse<ReviewDetail> getReview(@PathVariable Long reviewId) {
        return ApiResponse.ok(reviewService.getReview(reviewId));
    }

    @GetMapping("/api/projects/{projectId}/reviews")
    public ApiResponse<List<ReviewRecord>> listProjectReviews(
            @PathVariable Long projectId,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.ok(reviewService.listProjectReviews(projectId, limit == null ? 20 : limit));
    }

    @GetMapping("/api/reviews/{reviewId}/events")
    public ApiResponse<ReviewEventDetail> getReviewEvents(@PathVariable Long reviewId) {
        return ApiResponse.ok(reviewService.getReviewEvents(reviewId));
    }

    @PatchMapping("/api/review-issues/{issueId}")
    public ApiResponse<ReviewIssue> updateIssue(@PathVariable Long issueId, @RequestBody UpdateIssueRequest request) {
        return ApiResponse.ok(reviewService.updateIssueStatus(issueId, request.status(), request.note()));
    }

    public record CreateReviewRequest(
            String sourceType,
            String baseBranch,
            String compareBranch,
            String diffText,
            String mode,
            List<String> selectedFiles
    ) {
    }

    public record UpdateIssueRequest(
            String status,
            String note
    ) {
    }
}
