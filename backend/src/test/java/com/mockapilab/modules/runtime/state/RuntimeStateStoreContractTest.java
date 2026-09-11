package com.mockapilab.modules.runtime.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeStateStoreContractTest {

    static Stream<RuntimeStateStore> provideStores() {
        // 1. In-memory implementation
        InMemoryRuntimeStateStore inMemory = new InMemoryRuntimeStateStore();

        // 2. Redis-backed implementation with test template
        StringRedisTemplate template = new MockRedisTemplateBuilder().build();
        RedisRuntimeStateStore redisStore = new RedisRuntimeStateStore(template, new ObjectMapper());

        return Stream.of(inMemory, redisStore);
    }

    @ParameterizedTest
    @MethodSource("provideStores")
    @DisplayName("Contract: CRUD Lifecycle with Single Entity")
    void testCrudLifecycle(RuntimeStateStore store) {
        UUID runtimeId = UUID.randomUUID();
        String path = "/users";
        String id = "u-42";
        Map<String, Object> entity = Map.of("id", id, "username", "alice_contract");

        // Save
        Map<String, Object> saved = store.saveEntity(runtimeId, path, id, entity);
        assertThat(saved).containsEntry("username", "alice_contract");

        // Exists
        assertThat(store.hasCollection(runtimeId, path)).isTrue();
        assertThat(store.getEntityCount(runtimeId)).isEqualTo(1);
        assertThat(store.getCollectionCount(runtimeId)).isEqualTo(1);

        // Get
        Optional<Map<String, Object>> retrieved = store.getEntity(runtimeId, path, id);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).containsEntry("username", "alice_contract");

        // List
        List<Map<String, Object>> list = store.getCollection(runtimeId, path);
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).containsEntry("id", id);

        // Delete
        boolean deleted = store.deleteEntity(runtimeId, path, id);
        assertThat(deleted).isTrue();
        assertThat(store.getEntity(runtimeId, path, id)).isEmpty();
        assertThat(store.getEntityCount(runtimeId)).isEqualTo(0);

        // Collection is still registered after entity deleted
        assertThat(store.hasCollection(runtimeId, path)).isTrue();
        assertThat(store.getCollectionCount(runtimeId)).isEqualTo(1);

        // Clear runtime
        store.clearRuntime(runtimeId);
        assertThat(store.hasCollection(runtimeId, path)).isFalse();
        assertThat(store.getCollectionCount(runtimeId)).isEqualTo(0);
    }

    @ParameterizedTest
    @MethodSource("provideStores")
    @DisplayName("Contract: Initializing empty collection preserves registration")
    void testEmptyCollectionRegistration(RuntimeStateStore store) {
        UUID runtimeId = UUID.randomUUID();
        String path = "/empty_col";

        store.initializeCollection(runtimeId, path, Collections.emptyList());

        assertThat(store.hasCollection(runtimeId, path)).isTrue();
        assertThat(store.getCollection(runtimeId, path)).isEmpty();
        assertThat(store.getEntityCount(runtimeId)).isEqualTo(0);
        assertThat(store.getCollectionCount(runtimeId)).isEqualTo(1);
    }
}