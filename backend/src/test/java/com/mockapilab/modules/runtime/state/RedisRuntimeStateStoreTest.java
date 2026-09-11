package com.mockapilab.modules.runtime.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisRuntimeStateStoreTest {

    private ObjectMapper objectMapper;
    private StringRedisTemplate redisTemplate;
    private RedisRuntimeStateStore stateStore;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        redisTemplate = new MockRedisTemplateBuilder().build();
        stateStore = new RedisRuntimeStateStore(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("1. Save and get single entity in Redis collection")
    void testSaveAndGetEntity() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/users";
        String entityId = "user-123";
        Map<String, Object> entity = Map.of("id", entityId, "name", "Aadesh", "email", "aadesh@example.com", "role", "ADMIN");

        Map<String, Object> saved = stateStore.saveEntity(runtimeId, collection, entityId, entity);
        assertThat(saved).containsEntry("name", "Aadesh");

        Optional<Map<String, Object>> fetched = stateStore.getEntity(runtimeId, collection, entityId);
        assertThat(fetched).isPresent();
        assertThat(fetched.get()).containsEntry("id", "user-123");
        assertThat(fetched.get()).containsEntry("name", "Aadesh");
        assertThat(fetched.get()).containsEntry("email", "aadesh@example.com");
    }

    @Test
    @DisplayName("2. Retrieve all entities in collection")
    void testGetCollection() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/pets";

        stateStore.saveEntity(runtimeId, collection, "pet-1", Map.of("id", "pet-1", "name", "Fluffy", "species", "Cat"));
        stateStore.saveEntity(runtimeId, collection, "pet-2", Map.of("id", "pet-2", "name", "Barky", "species", "Dog"));

        List<Map<String, Object>> pets = stateStore.getCollection(runtimeId, collection);
        assertThat(pets).hasSize(2);
        assertThat(pets).extracting(p -> p.get("name")).containsExactlyInAnyOrder("Fluffy", "Barky");
    }

    @Test
    @DisplayName("3. Update existing entity attributes")
    void testUpdateEntity() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/products";
        String id = "prod-1";

        stateStore.saveEntity(runtimeId, collection, id, Map.of("id", id, "title", "Keyboard", "price", 49.99));
        stateStore.saveEntity(runtimeId, collection, id, Map.of("id", id, "title", "Mechanical Keyboard", "price", 79.99));

        Optional<Map<String, Object>> updated = stateStore.getEntity(runtimeId, collection, id);
        assertThat(updated).isPresent();
        assertThat(updated.get()).containsEntry("title", "Mechanical Keyboard");
        assertThat(updated.get()).containsEntry("price", 79.99);
        assertThat(stateStore.getEntityCount(runtimeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("4. Delete entity removes from hash but returns boolean")
    void testDeleteEntity() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/orders";
        String id = "order-1";

        stateStore.saveEntity(runtimeId, collection, id, Map.of("id", id, "total", 100));
        assertThat(stateStore.getEntity(runtimeId, collection, id)).isPresent();

        boolean deleted = stateStore.deleteEntity(runtimeId, collection, id);
        assertThat(deleted).isTrue();
        assertThat(stateStore.getEntity(runtimeId, collection, id)).isEmpty();

        boolean deleteAgain = stateStore.deleteEntity(runtimeId, collection, id);
        assertThat(deleteAgain).isFalse();
    }

    @Test
    @DisplayName("5. Runtime isolation - Runtime A does not see Runtime B state")
    void testRuntimeIsolation() {
        UUID runtimeA = UUID.randomUUID();
        UUID runtimeB = UUID.randomUUID();
        String collection = "/users";

        stateStore.saveEntity(runtimeA, collection, "user-1", Map.of("id", "user-1", "name", "Alice"));
        stateStore.saveEntity(runtimeB, collection, "user-2", Map.of("id", "user-2", "name", "Bob"));

        assertThat(stateStore.getEntity(runtimeA, collection, "user-1")).isPresent();
        assertThat(stateStore.getEntity(runtimeA, collection, "user-2")).isEmpty();

        assertThat(stateStore.getEntity(runtimeB, collection, "user-2")).isPresent();
        assertThat(stateStore.getEntity(runtimeB, collection, "user-1")).isEmpty();

        assertThat(stateStore.getCollection(runtimeA, collection)).hasSize(1);
        assertThat(stateStore.getCollection(runtimeB, collection)).hasSize(1);
    }

    @Test
    @DisplayName("6. Collection isolation - /users vs /products in same runtime")
    void testCollectionIsolation() {
        UUID runtimeId = UUID.randomUUID();

        stateStore.saveEntity(runtimeId, "/users", "1", Map.of("id", "1", "name", "User 1"));
        stateStore.saveEntity(runtimeId, "/products", "1", Map.of("id", "1", "title", "Product 1"));

        assertThat(stateStore.getEntity(runtimeId, "/users", "1").get()).containsEntry("name", "User 1");
        assertThat(stateStore.getEntity(runtimeId, "/products", "1").get()).containsEntry("title", "Product 1");

        assertThat(stateStore.getCollection(runtimeId, "/users")).hasSize(1);
        assertThat(stateStore.getCollection(runtimeId, "/products")).hasSize(1);
        assertThat(stateStore.getCollectionCount(runtimeId)).isEqualTo(2);
        assertThat(stateStore.getEntityCount(runtimeId)).isEqualTo(2);
    }

    @Test
    @DisplayName("7. Empty collection semantics - initialized empty collection exists in index")
    void testEmptyCollectionSemantics() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/categories";

        assertThat(stateStore.hasCollection(runtimeId, collection)).isFalse();

        stateStore.initializeCollection(runtimeId, collection, Collections.emptyList());

        // Collection index is the source of truth
        assertThat(stateStore.hasCollection(runtimeId, collection)).isTrue();
        assertThat(stateStore.getCollection(runtimeId, collection)).isEmpty();
        assertThat(stateStore.getCollectionCount(runtimeId)).isEqualTo(1);
        assertThat(stateStore.getEntityCount(runtimeId)).isEqualTo(0);
    }

    @Test
    @DisplayName("8. Collection cleanup semantics - deleting last entity retains collection registration")
    void testCollectionRegistrationRetainedAfterEntityDeletion() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/items";

        stateStore.saveEntity(runtimeId, collection, "item-1", Map.of("id", "item-1", "name", "Gadget"));
        assertThat(stateStore.hasCollection(runtimeId, collection)).isTrue();

        stateStore.deleteEntity(runtimeId, collection, "item-1");

        // The collection remains registered in the index even when empty
        assertThat(stateStore.hasCollection(runtimeId, collection)).isTrue();
        assertThat(stateStore.getEntityCount(runtimeId)).isEqualTo(0);
        assertThat(stateStore.getCollectionCount(runtimeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("9. clearRuntime removes all collection hashes and index registration")
    void testClearRuntime() {
        UUID runtimeId = UUID.randomUUID();

        stateStore.saveEntity(runtimeId, "/users", "u1", Map.of("id", "u1", "name", "User 1"));
        stateStore.saveEntity(runtimeId, "/orders", "o1", Map.of("id", "o1", "total", 50));
        assertThat(stateStore.getCollectionCount(runtimeId)).isEqualTo(2);
        assertThat(stateStore.getEntityCount(runtimeId)).isEqualTo(2);

        stateStore.clearRuntime(runtimeId);

        assertThat(stateStore.hasCollection(runtimeId, "/users")).isFalse();
        assertThat(stateStore.hasCollection(runtimeId, "/orders")).isFalse();
        assertThat(stateStore.getCollection(runtimeId, "/users")).isEmpty();
        assertThat(stateStore.getCollectionCount(runtimeId)).isEqualTo(0);
        assertThat(stateStore.getEntityCount(runtimeId)).isEqualTo(0);
    }

    @Test
    @DisplayName("10. Entity count across multiple collections")
    void testEntityCountAcrossCollections() {
        UUID runtimeId = UUID.randomUUID();

        stateStore.saveEntity(runtimeId, "/users", "u1", Map.of("id", "u1"));
        stateStore.saveEntity(runtimeId, "/users", "u2", Map.of("id", "u2"));
        stateStore.saveEntity(runtimeId, "/users", "u3", Map.of("id", "u3"));

        stateStore.saveEntity(runtimeId, "/items", "i1", Map.of("id", "i1"));
        stateStore.saveEntity(runtimeId, "/items", "i2", Map.of("id", "i2"));

        assertThat(stateStore.getEntityCount(runtimeId)).isEqualTo(5);
        assertThat(stateStore.getCollectionCount(runtimeId)).isEqualTo(2);
    }

    @Test
    @DisplayName("11. Complex JSON serialization and deserialization in Redis")
    void testComplexSerialization() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/complex";
        String id = "comp-1";

        Map<String, Object> complex = new LinkedHashMap<>();
        complex.put("id", id);
        complex.put("name", "Complex Object");
        complex.put("score", 98.6);
        complex.put("active", true);
        complex.put("tags", List.of("alpha", "beta", "gamma"));
        complex.put("metadata", Map.of("region", "ap-south-1", "zone", "in-mum-1"));

        stateStore.saveEntity(runtimeId, collection, id, complex);

        Optional<Map<String, Object>> fetched = stateStore.getEntity(runtimeId, collection, id);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().get("name")).isEqualTo("Complex Object");
        assertThat(fetched.get().get("score")).isEqualTo(98.6);
        assertThat(fetched.get().get("active")).isEqualTo(true);
        assertThat(fetched.get().get("tags")).isInstanceOf(List.class);
        assertThat(fetched.get().get("metadata")).isInstanceOf(Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("12. Redis failure behavior throws RuntimeStateException (never silent empty state)")
    void testRedisFailureThrowsException() {
        StringRedisTemplate failingTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);

        when(failingTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
        when(failingTemplate.opsForSet()).thenReturn(setOps);
        when(hashOps.entries(anyString())).thenThrow(new QueryTimeoutException("Redis connection timed out"));
        when(setOps.isMember(anyString(), any(Object.class))).thenThrow(new QueryTimeoutException("Redis timeout"));

        RedisRuntimeStateStore failingStore = new RedisRuntimeStateStore(failingTemplate, objectMapper);
        UUID runtimeId = UUID.randomUUID();

        assertThatThrownBy(() -> failingStore.getCollection(runtimeId, "/users"))
                .isInstanceOf(RuntimeStateException.class)
                .hasMessageContaining("Failed to retrieve collection from Redis");

        assertThatThrownBy(() -> failingStore.hasCollection(runtimeId, "/users"))
                .isInstanceOf(RuntimeStateException.class)
                .hasMessageContaining("Failed to check collection existence in Redis");
    }

    @Test
    @DisplayName("13. Multi-instance proof - Separate Java instances share same Redis state")
    void testMultiInstanceSharedState() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/devices";

        // Instance 1 writes
        RedisRuntimeStateStore instance1 = new RedisRuntimeStateStore(redisTemplate, objectMapper);
        instance1.saveEntity(runtimeId, collection, "dev-1", Map.of("id", "dev-1", "model", "Router X"));

        // Instance 2 (completely separate Java object) reads from the same Redis template
        RedisRuntimeStateStore instance2 = new RedisRuntimeStateStore(redisTemplate, objectMapper);
        Optional<Map<String, Object>> fetched = instance2.getEntity(runtimeId, collection, "dev-1");

        assertThat(fetched).isPresent();
        assertThat(fetched.get()).containsEntry("model", "Router X");
        assertThat(instance2.hasCollection(runtimeId, collection)).isTrue();
        assertThat(instance2.getEntityCount(runtimeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("14. Concurrent saves across threads are thread-safe")
    void testConcurrentWrites() throws InterruptedException {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/events";
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    stateStore.saveEntity(runtimeId, collection, "evt-" + idx, Map.of("id", "evt-" + idx, "sequence", idx));
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(stateStore.getEntityCount(runtimeId)).isEqualTo(threadCount);
        assertThat(stateStore.getCollection(runtimeId, collection)).hasSize(threadCount);
    }
}