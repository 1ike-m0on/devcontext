package com.devcontext.adapters.persistence;

import com.devcontext.domain.decision.DecisionCard;
import com.devcontext.domain.decision.DecisionEvidence;
import com.devcontext.ports.decision.DecisionCardRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDecisionCardRepository implements DecisionCardRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<DecisionEvidence>> EVIDENCE_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<DecisionCard> rowMapper = (rs, rowNum) -> new DecisionCard(
            rs.getLong("id"),
            nullableLong(rs.getObject("project_id"), rs.getLong("project_id")),
            rs.getString("title"),
            rs.getString("scenario"),
            readList(rs.getString("options_json"), STRING_LIST),
            rs.getString("decision"),
            readList(rs.getString("reasons_json"), STRING_LIST),
            readList(rs.getString("trade_offs_json"), STRING_LIST),
            readList(rs.getString("applicable_when_json"), STRING_LIST),
            readList(rs.getString("not_applicable_when_json"), STRING_LIST),
            rs.getString("outcome"),
            readList(rs.getString("evidence_json"), EVIDENCE_LIST),
            rs.getString("status"),
            readList(rs.getString("tags_json"), STRING_LIST),
            rs.getString("embedding_status"),
            nullableInstant(rs.getString("embedding_updated_at")),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    public JdbcDecisionCardRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public DecisionCard save(DecisionCard card) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO decision_card (
                        project_id, title, scenario, options_json, decision, reasons_json,
                        trade_offs_json, applicable_when_json, not_applicable_when_json,
                        outcome, evidence_json, status, tags_json, embedding_status,
                        embedding_updated_at, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            setNullableLong(statement, 1, card.projectId());
            statement.setString(2, card.title());
            statement.setString(3, card.scenario());
            statement.setString(4, writeJson(card.options()));
            statement.setString(5, card.decision());
            statement.setString(6, writeJson(card.reasons()));
            statement.setString(7, writeJson(card.tradeOffs()));
            statement.setString(8, writeJson(card.applicableWhen()));
            statement.setString(9, writeJson(card.notApplicableWhen()));
            statement.setString(10, card.outcome());
            statement.setString(11, writeJson(card.evidence()));
            statement.setString(12, card.status());
            statement.setString(13, writeJson(card.tags()));
            statement.setString(14, card.embeddingStatus());
            setNullableInstant(statement, 15, card.embeddingUpdatedAt());
            statement.setString(16, card.createdAt().toString());
            statement.setString(17, card.updatedAt().toString());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new DecisionCard(
                key == null ? null : key.longValue(),
                card.projectId(),
                card.title(),
                card.scenario(),
                card.options(),
                card.decision(),
                card.reasons(),
                card.tradeOffs(),
                card.applicableWhen(),
                card.notApplicableWhen(),
                card.outcome(),
                card.evidence(),
                card.status(),
                card.tags(),
                card.embeddingStatus(),
                card.embeddingUpdatedAt(),
                card.createdAt(),
                card.updatedAt()
        );
    }

    @Override
    public Optional<DecisionCard> findById(Long decisionId) {
        List<DecisionCard> cards = jdbcTemplate.query("SELECT * FROM decision_card WHERE id = ?", rowMapper, decisionId);
        return cards.stream().findFirst();
    }

    @Override
    public List<DecisionCard> findAll() {
        return jdbcTemplate.query("SELECT * FROM decision_card ORDER BY updated_at DESC, id DESC", rowMapper);
    }

    @Override
    public List<DecisionCard> findRelevantToProject(Long projectId) {
        return jdbcTemplate.query("""
                SELECT * FROM decision_card
                WHERE project_id IS NULL OR project_id = ?
                ORDER BY updated_at DESC, id DESC
                """, rowMapper, projectId);
    }

    @Override
    public List<DecisionCard> findByIds(Collection<Long> decisionIds) {
        if (decisionIds == null || decisionIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", decisionIds.stream().map(ignored -> "?").toList());
        return jdbcTemplate.query(
                "SELECT * FROM decision_card WHERE id IN (" + placeholders + ")",
                rowMapper,
                decisionIds.toArray()
        );
    }

    @Override
    public DecisionCard updateEmbeddingStatus(Long decisionId, String embeddingStatus, Instant embeddingUpdatedAt) {
        jdbcTemplate.update("""
                UPDATE decision_card
                SET embedding_status = ?, embedding_updated_at = ?
                WHERE id = ?
                """, embeddingStatus, embeddingUpdatedAt == null ? null : embeddingUpdatedAt.toString(), decisionId);
        return findById(decisionId)
                .orElseThrow(() -> new IllegalStateException("Decision card not found after embedding status update"));
    }

    @Override
    public DecisionCard updateStatus(Long decisionId, String status, Instant updatedAt) {
        jdbcTemplate.update("""
                UPDATE decision_card
                SET status = ?, updated_at = ?
                WHERE id = ?
                """, status, updatedAt.toString(), decisionId);
        return findById(decisionId)
                .orElseThrow(() -> new IllegalStateException("Decision card not found after status update"));
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private void setNullableInstant(PreparedStatement statement, int index, Instant value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.toString());
        }
    }

    private Long nullableLong(Object rawValue, long longValue) {
        return rawValue == null ? null : longValue;
    }

    private Instant nullableInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize decision card JSON field", e);
        }
    }

    private <T> T readList(String json, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse decision card JSON field", e);
        }
    }
}
