package com.devcontext.adapters.vector;

import com.devcontext.domain.knowledge.EmbeddingVector;
import com.devcontext.domain.knowledge.VectorDocument;
import com.devcontext.domain.knowledge.VectorQuery;
import com.devcontext.domain.knowledge.VectorSearchHit;
import com.devcontext.ports.knowledge.VectorStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "devcontext.vector.provider", havingValue = "jdbc", matchIfMissing = true)
public class JdbcVectorStore implements VectorStore {

    private static final TypeReference<List<Double>> DOUBLE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> METADATA_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcVectorStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void upsert(VectorDocument document) {
        String now = Instant.now().toString();
        int updated = jdbcTemplate.update("""
                UPDATE vector_document
                SET collection = ?, source_id = ?, embedding_json = ?, metadata_json = ?, updated_at = ?
                WHERE vector_id = ?
                """,
                document.collection(),
                document.sourceId(),
                writeJson(document.embedding().values()),
                writeJson(document.metadata()),
                now,
                document.vectorId()
        );
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO vector_document (
                        vector_id, collection, source_id, embedding_json, metadata_json, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    document.vectorId(),
                    document.collection(),
                    document.sourceId(),
                    writeJson(document.embedding().values()),
                    writeJson(document.metadata()),
                    now,
                    now
            );
        }
    }

    @Override
    public List<VectorSearchHit> search(VectorQuery query) {
        List<VectorRow> rows = query.sourceId() == null
                ? jdbcTemplate.query("""
                        SELECT vector_id, embedding_json, metadata_json
                        FROM vector_document
                        WHERE collection = ?
                        """, (rs, rowNum) -> new VectorRow(
                        rs.getString("vector_id"),
                        readVector(rs.getString("embedding_json")),
                        readMetadata(rs.getString("metadata_json"))
                ), query.collection())
                : jdbcTemplate.query("""
                        SELECT vector_id, embedding_json, metadata_json
                        FROM vector_document
                        WHERE collection = ? AND source_id = ?
                        """, (rs, rowNum) -> new VectorRow(
                        rs.getString("vector_id"),
                        readVector(rs.getString("embedding_json")),
                        readMetadata(rs.getString("metadata_json"))
                ), query.collection(), query.sourceId());

        return rows.stream()
                .filter(row -> matchesFilters(row.metadata(), query.filters()))
                .map(row -> new VectorSearchHit(row.vectorId(), cosine(query.embedding(), row.embedding())))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(VectorSearchHit::score).reversed())
                .limit(query.topK())
                .toList();
    }

    @Override
    public void deleteBySourceId(String collection, Long sourceId) {
        jdbcTemplate.update("DELETE FROM vector_document WHERE collection = ? AND source_id = ?", collection, sourceId);
    }

    private double cosine(EmbeddingVector left, EmbeddingVector right) {
        List<Double> a = left.values();
        List<Double> b = right.values();
        int size = Math.min(a.size(), b.size());
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < size; i++) {
            dot += a.get(i) * b.get(i);
            leftNorm += a.get(i) * a.get(i);
            rightNorm += b.get(i) * b.get(i);
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize vector document field", e);
        }
    }

    private EmbeddingVector readVector(String json) {
        try {
            return new EmbeddingVector(objectMapper.readValue(json, DOUBLE_LIST));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse vector embedding", e);
        }
    }

    private Map<String, Object> readMetadata(String json) {
        try {
            return objectMapper.readValue(json, METADATA_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse vector metadata", e);
        }
    }

    private boolean matchesFilters(Map<String, Object> metadata, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            if (!matchesFilter(metadata.get(filter.getKey()), filter.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesFilter(Object actual, Object expected) {
        if (expected == null) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        if (expected instanceof Iterable<?> values) {
            for (Object value : values) {
                if (String.valueOf(actual).equals(String.valueOf(value))) {
                    return true;
                }
            }
            return false;
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    private record VectorRow(String vectorId, EmbeddingVector embedding, Map<String, Object> metadata) {
    }
}
