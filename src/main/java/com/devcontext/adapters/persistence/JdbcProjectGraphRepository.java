package com.devcontext.adapters.persistence;

import com.devcontext.domain.graph.ProjectGraphEdge;
import com.devcontext.domain.graph.ProjectGraphNode;
import com.devcontext.ports.graph.ProjectGraphRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProjectGraphRepository implements ProjectGraphRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ProjectGraphNode> nodeRowMapper = (rs, rowNum) -> new ProjectGraphNode(
            rs.getLong("id"),
            rs.getLong("project_id"),
            rs.getString("node_type"),
            rs.getString("stable_key"),
            rs.getString("label"),
            rs.getString("source_path"),
            rs.getString("evidence_type"),
            rs.getString("source_kind"),
            rs.getString("source_reliability"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );
    private final RowMapper<ProjectGraphEdge> edgeRowMapper = (rs, rowNum) -> new ProjectGraphEdge(
            rs.getLong("id"),
            rs.getLong("project_id"),
            rs.getString("edge_type"),
            rs.getString("stable_key"),
            rs.getString("from_node_key"),
            rs.getString("to_node_key"),
            rs.getString("label"),
            rs.getString("source_path"),
            rs.getString("evidence_type"),
            rs.getString("source_kind"),
            rs.getString("source_reliability"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    public JdbcProjectGraphRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void replaceProjectGraph(Long projectId, List<ProjectGraphNode> nodes, List<ProjectGraphEdge> edges) {
        jdbcTemplate.update("DELETE FROM project_graph_edge WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM project_graph_node WHERE project_id = ?", projectId);
        for (ProjectGraphNode node : nodes) {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO project_graph_node (
                            project_id, node_type, stable_key, label, source_path,
                            evidence_type, source_kind, source_reliability, created_at, updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.NO_GENERATED_KEYS);
                statement.setLong(1, node.projectId());
                statement.setString(2, node.nodeType());
                statement.setString(3, node.stableKey());
                statement.setString(4, node.label());
                statement.setString(5, node.sourcePath());
                statement.setString(6, node.evidenceType());
                statement.setString(7, node.sourceKind());
                statement.setString(8, node.sourceReliability());
                statement.setString(9, node.createdAt().toString());
                statement.setString(10, node.updatedAt().toString());
                return statement;
            });
        }
        for (ProjectGraphEdge edge : edges) {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO project_graph_edge (
                            project_id, edge_type, stable_key, from_node_key, to_node_key, label,
                            source_path, evidence_type, source_kind, source_reliability, created_at, updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.NO_GENERATED_KEYS);
                statement.setLong(1, edge.projectId());
                statement.setString(2, edge.edgeType());
                statement.setString(3, edge.stableKey());
                statement.setString(4, edge.fromNodeKey());
                statement.setString(5, edge.toNodeKey());
                statement.setString(6, edge.label());
                statement.setString(7, edge.sourcePath());
                statement.setString(8, edge.evidenceType());
                statement.setString(9, edge.sourceKind());
                statement.setString(10, edge.sourceReliability());
                statement.setString(11, edge.createdAt().toString());
                statement.setString(12, edge.updatedAt().toString());
                return statement;
            });
        }
    }

    @Override
    public List<ProjectGraphNode> findNodesByProjectId(Long projectId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM project_graph_node
                WHERE project_id = ?
                ORDER BY node_type, stable_key
                """, nodeRowMapper, projectId);
    }

    @Override
    public List<ProjectGraphEdge> findEdgesByProjectId(Long projectId) {
        return jdbcTemplate.query("""
                SELECT *
                FROM project_graph_edge
                WHERE project_id = ?
                ORDER BY edge_type, stable_key
                """, edgeRowMapper, projectId);
    }

    @Override
    public Optional<ProjectGraphNode> findNodeByStableKey(Long projectId, String stableKey) {
        List<ProjectGraphNode> nodes = jdbcTemplate.query("""
                SELECT *
                FROM project_graph_node
                WHERE project_id = ? AND stable_key = ?
                """, nodeRowMapper, projectId, stableKey);
        return nodes.stream().findFirst();
    }
}
