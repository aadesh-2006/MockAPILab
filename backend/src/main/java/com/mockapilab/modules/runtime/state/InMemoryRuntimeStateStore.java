package com.mockapilab.modules.runtime.state;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of {@link RuntimeStateStore}.
 * <p>
 * State is strictly isolated per {@code runtimeId}, partitioned by collection name,
 * and maintains insertion ordering using synchronized LinkedHashMaps.
 */
@Component
@ConditionalOnProperty(name = "mockapilab.runtime.state-store", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryRuntimeStateStore implements RuntimeStateStore {

    // runtimeId -> (collectionPath -> (entityId -> entityAttributes))
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Map<String, Map<String, Object>>>> state = new ConcurrentHashMap<>();

    @Override
    public List<Map<String, Object>> getCollection(UUID runtimeId, String collectionPath) {
        String normalizedPath = normalizeCollectionPath(collectionPath);
        ConcurrentHashMap<String, Map<String, Map<String, Object>>> runtimeData = state.get(runtimeId);
        if (runtimeData == null) {
            return Collections.emptyList();
        }

        Map<String, Map<String, Object>> collection = runtimeData.get(normalizedPath);
        if (collection == null) {
            return Collections.emptyList();
        }

        synchronized (collection) {
            return new ArrayList<>(collection.values());
        }
    }

    @Override
    public boolean hasCollection(UUID runtimeId, String collectionPath) {
        String normalizedPath = normalizeCollectionPath(collectionPath);
        ConcurrentHashMap<String, Map<String, Map<String, Object>>> runtimeData = state.get(runtimeId);
        return runtimeData != null && runtimeData.containsKey(normalizedPath);
    }

    @Override
    public Optional<Map<String, Object>> getEntity(UUID runtimeId, String collectionPath, String entityId) {
        String normalizedPath = normalizeCollectionPath(collectionPath);
        ConcurrentHashMap<String, Map<String, Map<String, Object>>> runtimeData = state.get(runtimeId);
        if (runtimeData == null) {
            return Optional.empty();
        }

        Map<String, Map<String, Object>> collection = runtimeData.get(normalizedPath);
        if (collection == null) {
            return Optional.empty();
        }

        synchronized (collection) {
            Map<String, Object> entity = collection.get(entityId);
            return entity != null ? Optional.of(new LinkedHashMap<>(entity)) : Optional.empty();
        }
    }

    @Override
    public Map<String, Object> saveEntity(UUID runtimeId, String collectionPath, String entityId, Map<String, Object> entity) {
        String normalizedPath = normalizeCollectionPath(collectionPath);
        ConcurrentHashMap<String, Map<String, Map<String, Object>>> runtimeData =
                state.computeIfAbsent(runtimeId, k -> new ConcurrentHashMap<>());

        Map<String, Map<String, Object>> collection =
                runtimeData.computeIfAbsent(normalizedPath, k -> Collections.synchronizedMap(new LinkedHashMap<>()));

        Map<String, Object> entityCopy = new LinkedHashMap<>(entity);
        synchronized (collection) {
            collection.put(entityId, entityCopy);
        }
        return entityCopy;
    }

    @Override
    public boolean deleteEntity(UUID runtimeId, String collectionPath, String entityId) {
        String normalizedPath = normalizeCollectionPath(collectionPath);
        ConcurrentHashMap<String, Map<String, Map<String, Object>>> runtimeData = state.get(runtimeId);
        if (runtimeData == null) {
            return false;
        }

        Map<String, Map<String, Object>> collection = runtimeData.get(normalizedPath);
        if (collection == null) {
            return false;
        }

        synchronized (collection) {
            return collection.remove(entityId) != null;
        }
    }

    @Override
    public void initializeCollection(UUID runtimeId, String collectionPath, List<Map<String, Object>> seedEntities) {
        String normalizedPath = normalizeCollectionPath(collectionPath);
        ConcurrentHashMap<String, Map<String, Map<String, Object>>> runtimeData =
                state.computeIfAbsent(runtimeId, k -> new ConcurrentHashMap<>());

        Map<String, Map<String, Object>> collection =
                runtimeData.computeIfAbsent(normalizedPath, k -> Collections.synchronizedMap(new LinkedHashMap<>()));

        synchronized (collection) {
            for (Map<String, Object> seed : seedEntities) {
                String id = extractOrGenerateId(seed);
                collection.put(id, new LinkedHashMap<>(seed));
            }
        }
    }

    @Override
    public void clearRuntime(UUID runtimeId) {
        state.remove(runtimeId);
    }

    @Override
    public int getEntityCount(UUID runtimeId) {
        ConcurrentHashMap<String, Map<String, Map<String, Object>>> runtimeData = state.get(runtimeId);
        if (runtimeData == null) {
            return 0;
        }

        int count = 0;
        for (Map<String, Map<String, Object>> col : runtimeData.values()) {
            synchronized (col) {
                count += col.size();
            }
        }
        return count;
    }

    @Override
    public int getCollectionCount(UUID runtimeId) {
        ConcurrentHashMap<String, Map<String, Map<String, Object>>> runtimeData = state.get(runtimeId);
        return runtimeData == null ? 0 : runtimeData.size();
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
}