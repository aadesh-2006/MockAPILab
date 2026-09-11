package com.mockapilab.modules.runtime.state;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * State store interface for runtime data storage and isolation.
 */
public interface RuntimeStateStore {

    /**
     * Retrieves all entities in a given collection for a runtime.
     */
    List<Map<String, Object>> getCollection(UUID runtimeId, String collectionPath);

    /**
     * Checks if a collection has been initialized or populated in state.
     */
    boolean hasCollection(UUID runtimeId, String collectionPath);

    /**
     * Retrieves a single entity by ID in a given collection.
     */
    Optional<Map<String, Object>> getEntity(UUID runtimeId, String collectionPath, String entityId);

    /**
     * Saves or updates an entity in a collection.
     */
    Map<String, Object> saveEntity(UUID runtimeId, String collectionPath, String entityId, Map<String, Object> entity);

    /**
     * Deletes an entity by ID from a collection.
     *
     * @return true if the entity existed and was removed, false otherwise
     */
    boolean deleteEntity(UUID runtimeId, String collectionPath, String entityId);

    /**
     * Initializes or seeds a collection with initial mock entities.
     */
    void initializeCollection(UUID runtimeId, String collectionPath, List<Map<String, Object>> seedEntities);

    /**
     * Clears all state associated with a runtime instance.
     */
    void clearRuntime(UUID runtimeId);

    /**
     * Returns the total count of stored entities for a runtime instance.
     */
    int getEntityCount(UUID runtimeId);

    /**
     * Returns the number of distinct collections stored for a runtime instance.
     */
    int getCollectionCount(UUID runtimeId);
}