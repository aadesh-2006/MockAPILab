package com.mockapilab.modules.runtime.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Production-ready Redis-backed implementation of {@link RuntimeStateStore}.
 * <p>
 * State layout:
 * <ul>
 *     <li>Entity Hash: {@code mockapi:runtime:{runtimeId}:collection:{collectionPath}} -> (entityId -> JSON string)</li>
 *     <li>Collection Index: {@code mockapi:runtime:{runtimeId}:collections} -> Set of collection paths</li>
 * </ul>
 * <p>
 * Semantics:
 * <ul>
 *     <li>Collection existence is defined by membership in the collection index set (enabling empty collections).</li>
 *     <li>Deleting the last entity from a collection does not remove its registration from the index set.</li>
 *     <li>{@link #clearRuntime(UUID)} deletes all collection hashes and the index set for the runtime.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "mockapilab.runtime.state-store", havingValue = "redis")
public class RedisRuntimeStateStore implements RuntimeStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRuntimeStateStore.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisRuntimeStateStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Map<String, Object>> getCollection(UUID runtimeId, String collectionPath) {
        String normalizedPath = normalizeCollectionPath(collectionPath);
        String collectionKey = getCollectionKey(runtimeId, normalizedPath);

        try {
            Map<Object, Object> rawEntries = redisTemplate.opsForHash().entries(collectionKey);
            if (rawEntries == null || rawEntries.isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> result = new ArrayList<>(rawEntries.size());
            for (Object jsonVal : rawEntries.values()) {
                if (jsonVal instanceof String jsonStr) {
                    result.add(deserializeEntity(jsonStr));
                }
            }
            return result;
        } catch (DataAccessException e) {
            log.error("Redis error fetching collection '{}' for runtime '{}': {}", normalizedPath, runtimeId, e.getMessage());
            throw new RuntimeStateException("Failed to retrieve collection from Redis: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean hasCollection(UUID runtimeId, String collectionPath) {
        String normalizedPath = normalizeCollectionPath(collectionPath);
        String indexKey = getCollectionsIndexKey(runtimeId);

        try {
            Boolean isMember = redisTemplate.opsForSet().isMember(indexKey, normalizedPath);
            return Boolean.TRUE.equals(isMember);
        } catch (DataAccessException e) {
            log.error("Redis error checking collection '{}' for runtime '{}': {}", normalizedPath, runtimeId, e.getMessage());
            throw new RuntimeStateException("Failed to check collection existence in Redis: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Map<String, Object>> getEntity(UUID runtimeId, String collectionPath, String entityId) {
        String normalizedPath = normalizeCollectionPath(collectionPath);
        String collectionKey = getCollectionKey(runtimeId, normalizedPath);

        try {
            Object rawJson = redisTemplate.opsForHash().get(collectionKey, entityId);
            if (rawJson instanceof String jsonStr) {
                return Optional.of(deserializeEntity(jsonStr));
            }
            return Optional.empty();
        } catch (DataAccessException e) {
            log.error("Redis error fetching entity '{}' in collection '{}' for runtime '{}': {}", entityId, normalizedPath, runtimeId, e.getMessage());
            throw new RuntimeStateException("Failed to retrieve entity from Redis: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> saveEntity(UUID runtimeId, String collectionPath, String entityId, Map<String, Object> entity) {
        String normalizedPath = normalizeCollectionPath(collectionPath);
        String collectionKey = getCollectionKey(runtimeId, normalizedPath);
        String indexKey = getCollectionsIndexKey(runtimeId);

        Map<String, Object> entityCopy = new LinkedHashMap<>(entity);
        String jsonStr = serializeEntity(entityCopy);

        try {
            redisTemplate.opsForHash().put(collectionKey, entityId, jsonStr);
            redisTemplate.opsForSet().add(indexKey, normalizedPath);
            return entityCopy;
        } catch (DataAccessException e) {
            log.error("Redis error saving entity '{}' in collection '{}' for runtime '{}': {}", entityId, normalizedPath, runtimeId, e.getMessage());
            throw new RuntimeStateException("Failed to save entity to Redis: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean deleteEntity(UUID runtimeId, String collectionPath, String entityId) {
        String normalizedPath = normalizeCollectionPath(collectionPath);
        String collectionKey = getCollectionKey(runtimeId, normalizedPath);

        try {
            Long removed = redisTemplate.opsForHash().delete(collectionKey, entityId);
            return removed != null && removed > 0;
        } catch (DataAccessException e) {
            log.error("Redis error deleting entity '{}' in collection '{}' for runtime '{}': {}", entityId, normalizedPath, runtimeId, e.getMessage());
            throw new RuntimeStateException("Failed to delete entity from Redis: " + e.getMessage(), e);
        }
    }

    @Override
    public void initializeCollection(UUID runtimeId, String collectionPath, List<Map<String, Object>> seedEntities) {
        String normalizedPath = normalizeCollectionPath(collectionPath);
        String collectionKey = getCollectionKey(runtimeId, normalizedPath);
        String indexKey = getCollectionsIndexKey(runtimeId);

        try {
            redisTemplate.opsForSet().add(indexKey, normalizedPath);

            if (seedEntities != null && !seedEntities.isEmpty()) {
                Map<String, String> hashEntries = new LinkedHashMap<>();
                for (Map<String, Object> seed : seedEntities) {
                    Map<String, Object> copy = new LinkedHashMap<>(seed);
                    String id = extractOrGenerateId(copy);
                    hashEntries.put(id, serializeEntity(copy));
                }
                redisTemplate.opsForHash().putAll(collectionKey, hashEntries);
            }
        } catch (DataAccessException e) {
            log.error("Redis error initializing collection '{}' for runtime '{}': {}", normalizedPath, runtimeId, e.getMessage());
            throw new RuntimeStateException("Failed to initialize collection in Redis: " + e.getMessage(), e);
        }
    }

    @Override
    public void clearRuntime(UUID runtimeId) {
        String indexKey = getCollectionsIndexKey(runtimeId);

        try {
            Set<String> collections = redisTemplate.opsForSet().members(indexKey);
            if (collections != null && !collections.isEmpty()) {
                for (String path : collections) {
                    redisTemplate.delete(getCollectionKey(runtimeId, path));
                }
            }
            redisTemplate.delete(indexKey);
        } catch (DataAccessException e) {
            log.error("Redis error clearing state for runtime '{}': {}", runtimeId, e.getMessage());
            throw new RuntimeStateException("Failed to clear runtime state in Redis: " + e.getMessage(), e);
        }
    }

    @Override
    public int getEntityCount(UUID runtimeId) {
        String indexKey = getCollectionsIndexKey(runtimeId);

        try {
            Set<String> collections = redisTemplate.opsForSet().members(indexKey);
            if (collections == null || collections.isEmpty()) {
                return 0;
            }

            int count = 0;
            for (String path : collections) {
                Long size = redisTemplate.opsForHash().size(getCollectionKey(runtimeId, path));
                if (size != null) {
                    count += size.intValue();
                }
            }
            return count;
        } catch (DataAccessException e) {
            log.error("Redis error calculating entity count for runtime '{}': {}", runtimeId, e.getMessage());
            throw new RuntimeStateException("Failed to calculate entity count from Redis: " + e.getMessage(), e);
        }
    }

    @Override
    public int getCollectionCount(UUID runtimeId) {
        String indexKey = getCollectionsIndexKey(runtimeId);

        try {
            Long size = redisTemplate.opsForSet().size(indexKey);
            return size != null ? size.intValue() : 0;
        } catch (DataAccessException e) {
            log.error("Redis error calculating collection count for runtime '{}': {}", runtimeId, e.getMessage());
            throw new RuntimeStateException("Failed to calculate collection count from Redis: " + e.getMessage(), e);
        }
    }

    @Override
    public void checkHealth() {
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
            if (pong == null || (!"PONG".equalsIgnoreCase(pong) && !pong.contains("PONG"))) {
                throw new RuntimeStateException("Redis ping did not return PONG");
            }
        } catch (DataAccessException e) {
            log.error("Redis health check ping failed: {}", e.getMessage());
            throw new RuntimeStateException("Redis state store is unreachable: " + e.getMessage(), e);
        }
    }

    private String getCollectionKey(UUID runtimeId, String normalizedPath) {
        return "mockapi:runtime:" + runtimeId + ":collection:" + normalizedPath;
    }

    private String getCollectionsIndexKey(UUID runtimeId) {
        return "mockapi:runtime:" + runtimeId + ":collections";
    }

    private String normalizeCollectionPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private String extractOrGenerateId(Map<String, Object> entity) {
        if (entity.containsKey("id") && entity.get("id") != null) {
            return String.valueOf(entity.get("id"));
        }
        for (Map.Entry<String, Object> entry : entity.entrySet()) {
            if (entry.getKey().toLowerCase().endsWith("id") && entry.getValue() != null) {
                return String.valueOf(entry.getValue());
            }
        }
        String generated = UUID.randomUUID().toString();
        entity.put("id", generated);
        return generated;
    }

    private String serializeEntity(Map<String, Object> entity) {
        try {
            return objectMapper.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            throw new RuntimeStateException("Failed to serialize entity to JSON: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> deserializeEntity(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE_REF);
        } catch (JsonProcessingException e) {
            throw new RuntimeStateException("Failed to deserialize entity JSON: " + e.getMessage(), e);
        }
    }
}