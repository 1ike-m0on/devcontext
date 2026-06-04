package com.devcontext.adapters.vector;

import com.devcontext.config.DevContextVectorProperties;
import com.devcontext.domain.knowledge.VectorDocument;
import com.devcontext.domain.knowledge.VectorQuery;
import com.devcontext.domain.knowledge.VectorSearchHit;
import com.devcontext.ports.knowledge.VectorStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "devcontext.vector.provider", havingValue = "qdrant")
public class QdrantVectorStore implements VectorStore {

    private final DevContextVectorProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Set<String> initializedCollections = ConcurrentHashMap.newKeySet();

    public QdrantVectorStore(DevContextVectorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.qdrant().timeout())
                .build();
    }

    @Override
    public void upsert(VectorDocument document) {
        ensureCollection(document.collection(), document.embedding().values().size());
        Map<String, Object> payload = new LinkedHashMap<>();
        if (document.metadata() != null) {
            payload.putAll(document.metadata());
        }
        payload.put("vectorId", document.vectorId());
        payload.put("collection", document.collection());
        if (document.sourceId() != null) {
            payload.put("sourceId", document.sourceId());
        }

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", pointId(document.vectorId()));
        point.put("vector", document.embedding().values());
        point.put("payload", payload);

        Map<String, Object> body = Map.of("points", List.of(point));
        send("PUT", "/collections/" + document.collection() + "/points?wait=true", body, Set.of(200, 201));
    }

    @Override
    public List<VectorSearchHit> search(VectorQuery query) {
        ensureCollection(query.collection(), query.embedding().values().size());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", query.embedding().values());
        body.put("limit", query.topK());
        body.put("with_payload", true);
        Map<String, Object> filter = buildFilter(query);
        if (!filter.isEmpty()) {
            body.put("filter", filter);
        }

        JsonNode response = send("POST", "/collections/" + query.collection() + "/points/search", body, Set.of(200));
        List<VectorSearchHit> hits = new ArrayList<>();
        for (JsonNode item : response.path("result")) {
            JsonNode payload = item.path("payload");
            String vectorId = payload.path("vectorId").asText(item.path("id").asText());
            hits.add(new VectorSearchHit(vectorId, item.path("score").asDouble()));
        }
        return hits;
    }

    @Override
    public void deleteBySourceId(String collection, Long sourceId) {
        if (sourceId == null) {
            return;
        }
        Map<String, Object> body = Map.of("filter", Map.of("must", List.of(Map.of(
                "key", "sourceId",
                "match", Map.of("value", sourceId)
        ))));
        send("POST", "/collections/" + collection + "/points/delete?wait=true", body, Set.of(200, 404));
    }

    private void ensureCollection(String collection, int dimensions) {
        if (initializedCollections.contains(collection)) {
            return;
        }
        HttpResponse<String> getResponse = sendRaw("GET", "/collections/" + collection, null);
        if (getResponse.statusCode() == 404) {
            Map<String, Object> body = Map.of("vectors", Map.of(
                    "size", dimensions,
                    "distance", properties.qdrant().distance()
            ));
            send("PUT", "/collections/" + collection, body, Set.of(200, 201));
        } else if (getResponse.statusCode() < 200 || getResponse.statusCode() >= 300) {
            throw new IllegalStateException("Qdrant collection check failed: HTTP "
                    + getResponse.statusCode() + " " + getResponse.body());
        }
        createPayloadIndexIfPossible(collection, "status", "keyword");
        createPayloadIndexIfPossible(collection, "projectScope", "keyword");
        createPayloadIndexIfPossible(collection, "sourceId", "integer");
        initializedCollections.add(collection);
    }

    private Map<String, Object> buildFilter(VectorQuery query) {
        List<Map<String, Object>> must = new ArrayList<>();
        if (query.sourceId() != null) {
            must.add(match("sourceId", query.sourceId()));
        }
        if (query.filters() != null) {
            Object status = query.filters().get("status");
            if (status != null) {
                must.add(match("status", status));
            }
        }

        Map<String, Object> filter = new LinkedHashMap<>();
        if (!must.isEmpty()) {
            filter.put("must", must);
        }
        List<Map<String, Object>> should = projectScopeMatches(query);
        if (!should.isEmpty()) {
            filter.put("should", should);
        }
        return filter;
    }

    private List<Map<String, Object>> projectScopeMatches(VectorQuery query) {
        if (query.filters() == null) {
            return List.of();
        }
        Object value = query.filters().get("projectScope");
        if (value == null) {
            value = query.filters().get("projectScopes");
        }
        if (value instanceof Iterable<?> values) {
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Object item : values) {
                matches.add(match("projectScope", item));
            }
            return matches;
        }
        return value == null ? List.of() : List.of(match("projectScope", value));
    }

    private Map<String, Object> match(String key, Object value) {
        return Map.of(
                "key", key,
                "match", Map.of("value", value)
        );
    }

    private void createPayloadIndexIfPossible(String collection, String fieldName, String fieldSchema) {
        Map<String, Object> body = Map.of(
                "field_name", fieldName,
                "field_schema", fieldSchema
        );
        HttpResponse<String> response = sendRaw("PUT", "/collections/" + collection + "/index?wait=true", body);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        String bodyText = response.body() == null ? "" : response.body().toLowerCase();
        if (response.statusCode() == 409 || bodyText.contains("already")) {
            return;
        }
    }

    private JsonNode send(String method, String path, Object body, Set<Integer> expectedStatuses) {
        HttpResponse<String> response = sendRaw(method, path, body);
        if (!expectedStatuses.contains(response.statusCode())) {
            throw new IllegalStateException("Qdrant request failed: HTTP "
                    + response.statusCode() + " " + response.body());
        }
        if (response.body() == null || response.body().isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse Qdrant response", e);
        }
    }

    private HttpResponse<String> sendRaw(String method, String path, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
                .timeout(properties.qdrant().timeout())
                .header("Content-Type", "application/json");
        if (properties.qdrant().apiKey() != null && !properties.qdrant().apiKey().isBlank()) {
            builder.header("api-key", properties.qdrant().apiKey());
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(writeJson(body)));
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Qdrant request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant request interrupted", e);
        }
    }

    private URI resolve(String path) {
        String baseUrl = properties.qdrant().baseUrl();
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBase + path);
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Qdrant request", e);
        }
    }

    private String pointId(String vectorId) {
        return UUID.nameUUIDFromBytes(vectorId.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
