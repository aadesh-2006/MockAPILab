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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
    private static final Duration TEMP_KEY_TTL = Duration.ofMinutes(5);
    private static final Duration DELETED_METADATA_TTL = Duration.ofDays(7);
    private static final DefaultRedisScript<Long> REGISTER_INDEX_IF_ACTIVE_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 0
            end
            redis.call('SADD', KEYS[1], ARGV[1])
            return 1
            """,
            Long.class
    );
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> MARK_RUNTIME_DELETED_SCRIPT = new DefaultRedisScript<>(
            """
            redis.call('SET', KEYS[2], '1', 'EX', tonumber(ARGV[1]))
            local members = redis.call('SMEMBERS', KEYS[1])
            redis.call('DEL', KEYS[1])
            return members
            """,
            List.class
    );
    private static final DefaultRedisScript<Long> PROMOTE_OR_CLEAR_COLLECTION_SCRIPT = new DefaultRedisScript<>(
            """
            local function isPositiveDecimal(s)
                return type(s) == 'string' and string.match(s, '^[1-9][0-9]*$') ~= nil
            end

            local function compareDecimal(a, b)
                if #a ~= #b then
                    return (#a < #b) and -1 or 1
                end
                if a == b then
                    return 0
                end
                return (a < b) and -1 or 1
            end

            local incoming = ARGV[1]
            local hasTemp = ARGV[2] == '1'
            local isZero = (incoming == '0')
            local isPos = isPositiveDecimal(incoming)

            if not isZero and not isPos then
                if hasTemp and KEYS[3] then
                    redis.call('DEL', KEYS[3])
                end
                return 0
            end

            local currentRaw = redis.call('GET', KEYS[2])
            if currentRaw then
                if isZero then
                    if hasTemp and KEYS[3] then
                        redis.call('DEL', KEYS[3])
                    end
                    return 0
                end
                if isPositiveDecimal(currentRaw) and compareDecimal(incoming, currentRaw) <= 0 then
                    if hasTemp and KEYS[3] then
                        redis.call('DEL', KEYS[3])
                    end
                    return 0
                end
            end

            if hasTemp then
                if redis.call('EXISTS', KEYS[3]) ~= 1 then
                    return -1
                end
                redis.call('RENAME', KEYS[3], KEYS[1])
                redis.call('PERSIST', KEYS[1])
            else
                redis.call('DEL', KEYS[1])
            end

            if isPos then
                redis.call('SET', KEYS[2], incoming)
            end
            return 1
            """,
            Long.class
    );

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
        String deletedKey = getRuntimeDeletedKey(runtimeId);

        Map<String, Object> entityCopy = new LinkedHashMap<>(entity);
        String jsonStr = serializeEntity(entityCopy);

        try {
            if (!registerIndexIfActive(indexKey, deletedKey, normalizedPath)) {
                log.warn("Rejected entity save for deleted runtime '{}' collection '{}'", runtimeId, normalizedPath);
                throw new RuntimeStateException("Cannot save entity for deleted runtime: " + runtimeId);
            }
            redisTemplate.opsForHash().put(collectionKey, entityId, jsonStr);
            if (isRuntimeDeleted(runtimeId)) {
                redisTemplate.delete(collectionKey);
                throw new RuntimeStateException("Cannot save entity for concurrently deleted runtime: " + runtimeId);
            }
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
    public boolean initializeCollection(UUID runtimeId, String collectionPath, List<Map<String, Object>> seedEntities, long fencingToken) {
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be non-negative (got " + fencingToken + ")");
        }

        String normalizedPath = normalizeCollectionPath(collectionPath);
        String collectionKey = getCollectionKey(runtimeId, normalizedPath);
        String fenceKey = getFenceKey(collectionKey);
        String indexKey = getCollectionsIndexKey(runtimeId);
        String deletedKey = getRuntimeDeletedKey(runtimeId);

        // 1. Validate token (above) and serialize all hash entries in memory before any Redis mutation
        Map<String, String> hashEntries = new LinkedHashMap<>();
        if (seedEntities != null && !seedEntities.isEmpty()) {
            for (Map<String, Object> seed : seedEntities) {
                Map<String, Object> copy = new LinkedHashMap<>(seed);
                String id = extractOrGenerateId(copy);
                hashEntries.put(id, serializeEntity(copy));
            }
        }

        String tempKey = null;
        boolean promoted = false;
        try {
            // 2. Fast tombstone check
            if (isRuntimeDeleted(runtimeId)) {
                log.warn("Rejected collection initialization for deleted runtime '{}' collection '{}'",
                        runtimeId, normalizedPath);
                return false;
            }

            // 3. Stage temporary collection data (same Cluster slot as collectionKey/fenceKey)
            List<String> keys;
            String hasTempFlag;
            if (!hashEntries.isEmpty()) {
                tempKey = getTempKey(collectionKey);
                redisTemplate.opsForHash().putAll(tempKey, hashEntries);
                redisTemplate.expire(tempKey, TEMP_KEY_TTL);
                keys = List.of(collectionKey, fenceKey, tempKey);
                hasTempFlag = "1";
            } else {
                keys = List.of(collectionKey, fenceKey);
                hasTempFlag = "0";
            }

            // 4. Atomically register the path in indexKey only if runtime is active (same Cluster slot as deletedKey)
            if (!registerIndexIfActive(indexKey, deletedKey, normalizedPath)) {
                log.warn("Rejected index registration for deleted runtime '{}' collection '{}'",
                        runtimeId, normalizedPath);
                return false;
            }

            // 5. Promote through fencing Lua script (note: indexKey/deletedKey are in a separate Cluster slot
            // from collectionKey/fenceKey/tempKey because existing collectionKey names do not hash-tag {runtimeId})
            Long result = redisTemplate.execute(
                    PROMOTE_OR_CLEAR_COLLECTION_SCRIPT,
                    keys,
                    String.valueOf(fencingToken),
                    hasTempFlag
            );
            if (result == null) {
                throw new RuntimeStateException("Redis atomic collection replacement returned null result");
            }
            if (result == -1L) {
                throw new RuntimeStateException("Redis temporary collection key disappeared prior to promotion");
            }
            if (result == 0L) {
                log.warn("Rejected stale or invalid collection replacement for runtime '{}' collection '{}' with fencingToken={}",
                        runtimeId, normalizedPath, fencingToken);
                return false;
            }
            promoted = true;

            // 6. Post-promotion tombstone check for cross-slot deletion that started between step 4 and step 5
            if (isRuntimeDeleted(runtimeId)) {
                // 7. Remove promoted collection and expire fence key
                redisTemplate.delete(collectionKey);
                redisTemplate.expire(fenceKey, DELETED_METADATA_TTL);
                log.warn("Rolled back collection write for concurrently deleted runtime '{}' collection '{}'",
                        runtimeId, normalizedPath);
                return false;
            }

            return true;
        } catch (DataAccessException e) {
            log.error("Redis error initializing collection '{}' for runtime '{}': {}", normalizedPath, runtimeId, e.getMessage());
            throw new RuntimeStateException("Failed to initialize collection in Redis: " + e.getMessage(), e);
        } finally {
            if (tempKey != null && !promoted) {
                try {
                    redisTemplate.delete(tempKey);
                } catch (DataAccessException cleanupEx) {
                    log.debug("Best-effort cleanup of temp key '{}' failed: {}", tempKey, cleanupEx.getMessage());
                }
            }
        }
    }

    @Override
    public void markRuntimeDeleted(UUID runtimeId) {
        String indexKey = getCollectionsIndexKey(runtimeId);
        String deletedKey = getRuntimeDeletedKey(runtimeId);

        List<?> rawMembers;
        try {
            rawMembers = redisTemplate.execute(
                    MARK_RUNTIME_DELETED_SCRIPT,
                    List.of(indexKey, deletedKey),
                    String.valueOf(DELETED_METADATA_TTL.toSeconds())
            );
        } catch (DataAccessException e) {
            log.error("Redis error executing deletion metadata script for runtime '{}': {}", runtimeId, e.getMessage());
            throw new RuntimeStateException("Failed to mark runtime deleted in Redis: " + e.getMessage(), e);
        }

        if (rawMembers == null) {
            throw new RuntimeStateException("Redis runtime deletion script returned null result for runtime: " + runtimeId);
        }

        List<String> failedCollections = new ArrayList<>();
        DataAccessException firstFailure = null;
        for (Object member : rawMembers) {
            if (member == null) {
                continue;
            }
            String path = String.valueOf(member);
            String collectionKey = getCollectionKey(runtimeId, path);
            try {
                redisTemplate.delete(collectionKey);
                redisTemplate.expire(getFenceKey(collectionKey), DELETED_METADATA_TTL);
            } catch (DataAccessException e) {
                log.error("Failed to clean up collection '{}' for deleted runtime '{}': {}", path, runtimeId, e.getMessage());
                failedCollections.add(path);
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }

        if (firstFailure != null) {
            throw new RuntimeStateException(
                    "Incomplete Redis cleanup for deleted runtime '" + runtimeId + "'; failed collections=" + failedCollections,
                    firstFailure
            );
        }
    }

    private boolean registerIndexIfActive(String indexKey, String deletedKey, String normalizedPath) {
        Long result = redisTemplate.execute(
                REGISTER_INDEX_IF_ACTIVE_SCRIPT,
                List.of(indexKey, deletedKey),
                normalizedPath
        );
        if (result == null) {
            throw new RuntimeStateException("Redis atomic index registration returned null result");
        }
        return result == 1L;
    }

    @Override
    public void clearRuntime(UUID runtimeId) {
        String indexKey = getCollectionsIndexKey(runtimeId);

        try {
            Set<String> collections = redisTemplate.opsForSet().members(indexKey);
            if (collections != null && !collections.isEmpty()) {
                for (String path : collections) {
                    String collectionKey = getCollectionKey(runtimeId, path);
                    redisTemplate.delete(collectionKey);
                    redisTemplate.delete(getFenceKey(collectionKey));
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

    private boolean isRuntimeDeleted(UUID runtimeId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(getRuntimeDeletedKey(runtimeId)));
    }

    private String getCollectionKey(UUID runtimeId, String normalizedPath) {
        return "mockapi:runtime:" + runtimeId + ":collection:" + normalizedPath;
    }

    private String getFenceKey(String collectionKey) {
        return "{" + collectionKey + "}:fence";
    }

    private String getTempKey(String collectionKey) {
        return "{" + collectionKey + "}:tmp:" + UUID.randomUUID();
    }

    private String getCollectionsIndexKey(UUID runtimeId) {
        return "mockapi:runtime:" + runtimeId + ":collections";
    }

    private String getRuntimeDeletedKey(UUID runtimeId) {
        return "{" + getCollectionsIndexKey(runtimeId) + "}:deleted";
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