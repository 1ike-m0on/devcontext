package com.devcontext.adapters.persistence;

import com.devcontext.domain.review.ReviewRecord;
import com.devcontext.ports.review.ReviewRecordRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReviewRecordRepository implements ReviewRecordRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ReviewRecord> rowMapper = (rs, rowNum) -> new ReviewRecord(
            rs.getLong("id"),
            rs.getLong("project_id"),
            rs.getLong("run_id"),
            rs.getString("base_branch"),
            rs.getString("compare_branch"),
            rs.getString("diff_hash"),
            rs.getDouble("score"),
            rs.getString("summary"),
            rs.getString("report_path"),
            Instant.parse(rs.getString("created_at"))
    );

    public JdbcReviewRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ReviewRecord save(ReviewRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO review_record (
                        project_id, run_id, base_branch, compare_branch, diff_hash,
                        score, summary, report_path, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, record.projectId());
            statement.setLong(2, record.runId());
            statement.setString(3, record.baseBranch());
            statement.setString(4, record.compareBranch());
            statement.setString(5, record.diffHash());
            statement.setDouble(6, record.score());
            statement.setString(7, record.summary());
            statement.setString(8, record.reportPath());
            statement.setString(9, record.createdAt().toString());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new ReviewRecord(
                key == null ? null : key.longValue(),
                record.projectId(),
                record.runId(),
                record.baseBranch(),
                record.compareBranch(),
                record.diffHash(),
                record.score(),
                record.summary(),
                record.reportPath(),
                record.createdAt()
        );
    }

    @Override
    public ReviewRecord updateReportPath(Long reviewId, String reportPath) {
        jdbcTemplate.update("UPDATE review_record SET report_path = ? WHERE id = ?", reportPath, reviewId);
        return findById(reviewId).orElseThrow();
    }

    @Override
    public Optional<ReviewRecord> findById(Long reviewId) {
        List<ReviewRecord> records = jdbcTemplate.query("SELECT * FROM review_record WHERE id = ?", rowMapper, reviewId);
        return records.stream().findFirst();
    }
}
