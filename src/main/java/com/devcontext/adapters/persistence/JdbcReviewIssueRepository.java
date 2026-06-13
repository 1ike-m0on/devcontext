package com.devcontext.adapters.persistence;

import com.devcontext.domain.review.ReviewIssue;
import com.devcontext.ports.review.ReviewIssueRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReviewIssueRepository implements ReviewIssueRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ReviewIssue> rowMapper = (rs, rowNum) -> new ReviewIssue(
            rs.getLong("id"),
            rs.getLong("review_id"),
            rs.getString("severity"),
            rs.getString("title"),
            rs.getString("file_path"),
            nullableInt(rs, "line_number"),
            rs.getString("description"),
            rs.getString("impact"),
            rs.getString("suggestion"),
            rs.getString("confidence"),
            rs.getString("status"),
            rs.getString("note"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    public JdbcReviewIssueRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ReviewIssue save(ReviewIssue issue) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO review_issue (
                        review_id, severity, title, file_path, line_number, description,
                        impact, suggestion, confidence, status, note, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, issue.reviewId());
            statement.setString(2, issue.severity());
            statement.setString(3, issue.title());
            statement.setString(4, issue.filePath());
            statement.setObject(5, issue.lineNumber());
            statement.setString(6, issue.description());
            statement.setString(7, issue.impact());
            statement.setString(8, issue.suggestion());
            statement.setString(9, issue.confidence());
            statement.setString(10, issue.status());
            statement.setString(11, issue.note());
            statement.setString(12, issue.createdAt().toString());
            statement.setString(13, issue.updatedAt().toString());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new ReviewIssue(
                key == null ? null : key.longValue(),
                issue.reviewId(),
                issue.severity(),
                issue.title(),
                issue.filePath(),
                issue.lineNumber(),
                issue.description(),
                issue.impact(),
                issue.suggestion(),
                issue.confidence(),
                issue.status(),
                issue.note(),
                issue.createdAt(),
                issue.updatedAt()
        );
    }

    @Override
    public List<ReviewIssue> findByReviewId(Long reviewId) {
        return jdbcTemplate.query("SELECT * FROM review_issue WHERE review_id = ? ORDER BY id ASC", rowMapper, reviewId);
    }

    @Override
    public List<ReviewIssue> findByReviewIds(List<Long> reviewIds) {
        if (reviewIds == null || reviewIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(reviewIds.size(), "?"));
        return jdbcTemplate.query(
                "SELECT * FROM review_issue WHERE review_id IN (" + placeholders + ") ORDER BY review_id ASC, id ASC",
                rowMapper,
                reviewIds.toArray()
        );
    }

    @Override
    public Optional<ReviewIssue> findById(Long issueId) {
        List<ReviewIssue> issues = jdbcTemplate.query("SELECT * FROM review_issue WHERE id = ?", rowMapper, issueId);
        return issues.stream().findFirst();
    }

    @Override
    public List<ReviewIssue> findRecentFeedbackByProjectId(Long projectId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 30));
        return jdbcTemplate.query("""
                SELECT i.*
                FROM review_issue i
                JOIN review_record r ON r.id = i.review_id
                WHERE r.project_id = ?
                  AND i.status IN ('accepted', 'fixed', 'false_positive', 'rejected')
                ORDER BY i.updated_at DESC, i.id DESC
                LIMIT ?
                """, rowMapper, projectId, safeLimit);
    }

    @Override
    public List<ReviewIssue> findRecentFeedbackByProjectIdBefore(Long projectId, Instant before, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 30));
        return jdbcTemplate.query("""
                SELECT i.*
                FROM review_issue i
                JOIN review_record r ON r.id = i.review_id
                WHERE r.project_id = ?
                  AND i.status IN ('accepted', 'fixed', 'false_positive', 'rejected')
                  AND i.updated_at <= ?
                ORDER BY i.updated_at DESC, i.id DESC
                LIMIT ?
                """, rowMapper, projectId, before.toString(), safeLimit);
    }

    @Override
    public ReviewIssue updateStatus(Long issueId, String status, String note) {
        jdbcTemplate.update("""
                UPDATE review_issue
                SET status = ?, note = ?, updated_at = ?
                WHERE id = ?
                """, status, note, Instant.now().toString(), issueId);
        return findById(issueId).orElseThrow();
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
