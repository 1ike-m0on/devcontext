package com.devcontext.ports.review;

import com.devcontext.domain.review.ReviewRecord;
import java.util.List;
import java.util.Optional;

public interface ReviewRecordRepository {

    ReviewRecord save(ReviewRecord record);

    ReviewRecord updateReportPath(Long reviewId, String reportPath);

    Optional<ReviewRecord> findById(Long reviewId);

    List<ReviewRecord> findByProjectId(Long projectId, int limit);
}
