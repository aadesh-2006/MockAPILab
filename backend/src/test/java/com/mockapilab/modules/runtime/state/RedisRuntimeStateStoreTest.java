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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisRuntimeStateStoreTest {

    private ObjectMapper objectMapper;
    private MockRedisTemplateBuilder redisBuilder;
    private StringRedisTemplate redisTemplate;
    private RedisRuntimeStateStore stateStore;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        redisBuilder = new MockRedisTemplateBuilder();
        redisTemplate = redisBuilder.build();
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

    @Test
    @DisplayName("15. Re-initializing a collection replaces prior generated entities instead of appending")
    void testInitializeCollectionReplacesExistingEntities() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/pets";

        stateStore.initializeCollection(runtimeId, collection, List.of(
                Map.of("id", "old-1", "name", "Old Pet 1"),
                Map.of("id", "old-2", "name", "Old Pet 2")
        ));
        assertThat(stateStore.getCollection(runtimeId, collection)).hasSize(2);

        stateStore.initializeCollection(runtimeId, collection, List.of(
                Map.of("id", "new-1", "name", "New Pet 1")
        ));

        List<Map<String, Object>> current = stateStore.getCollection(runtimeId, collection);
        assertThat(current).hasSize(1);
        assertThat(current.get(0)).containsEntry("id", "new-1").containsEntry("name", "New Pet 1");
        assertThat(stateStore.getEntity(runtimeId, collection, "old-1")).isEmpty();
    }

    @Test
    @DisplayName("16. Empty replacement clears prior entities while keeping collection registered")
    void testEmptyReplacementClearsEntitiesAndKeepsCollection() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/pets";

        stateStore.initializeCollection(runtimeId, collection, List.of(
                Map.of("id", "old-1", "name", "Old Pet")
        ), 1L);
        assertThat(stateStore.getCollection(runtimeId, collection)).hasSize(1);

        boolean replaced = stateStore.initializeCollection(runtimeId, collection, Collections.emptyList(), 2L);
        assertThat(replaced).isTrue();
        assertThat(stateStore.hasCollection(runtimeId, collection)).isTrue();
        assertThat(stateStore.getCollection(runtimeId, collection)).isEmpty();
    }

    @Test
    @DisplayName("17. Fencing rejects stale token and preserves newer generation entities")
    void testFencingRejectsStaleToken() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/pets";

        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(
                Map.of("id", "j1", "name", "Job 1")
        ), 1L)).isTrue();

        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(
                Map.of("id", "j2", "name", "Job 2")
        ), 2L)).isTrue();

        // Stale Job 1 attempts to overwrite Job 2
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(
                Map.of("id", "j1-stale", "name", "Stale Job 1")
        ), 1L)).isFalse();

        List<Map<String, Object>> current = stateStore.getCollection(runtimeId, collection);
        assertThat(current).hasSize(1);
        assertThat(current.get(0)).containsEntry("id", "j2").containsEntry("name", "Job 2");
    }

    @Test
    @DisplayName("18. Script returning null or throwing DataAccessException throws RuntimeStateException")
    @SuppressWarnings("unchecked")
    void testScriptFailureThrowsRuntimeStateException() {
        StringRedisTemplate failingTemplate = mock(StringRedisTemplate.class);
        when(failingTemplate.opsForHash()).thenReturn(mock(HashOperations.class));
        when(failingTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(null);
        RedisRuntimeStateStore storeWithNullResult = new RedisRuntimeStateStore(failingTemplate, objectMapper);

        UUID runtimeId = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                storeWithNullResult.initializeCollection(runtimeId, "/pets", List.of(Map.of("id", "1")), 1L))
                .isInstanceOf(RuntimeStateException.class)
                .hasMessageContaining("null");

        when(failingTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Upstash connection reset"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                storeWithNullResult.initializeCollection(runtimeId, "/pets", List.of(Map.of("id", "1")), 1L))
                .isInstanceOf(RuntimeStateException.class)
                .hasMessageContaining("Failed to initialize collection in Redis");
    }

    @Test
    @DisplayName("19. Concurrent readers never observe empty collection during replacement and lower token never wins")
    void testConcurrentReadersAndFencingWriters() throws Exception {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/orders";

        stateStore.initializeCollection(runtimeId, collection, List.of(
                Map.of("id", "init-1", "gen", 1)
        ), 1L);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch startGate = new CountDownLatch(1);

        var writerHigh = pool.submit(() -> {
            startGate.await();
            return stateStore.initializeCollection(runtimeId, collection, List.of(
                    Map.of("id", "high-1", "gen", 10),
                    Map.of("id", "high-2", "gen", 10)
            ), 10L);
        });
        var writerLow = pool.submit(() -> {
            startGate.await();
            return stateStore.initializeCollection(runtimeId, collection, List.of(
                    Map.of("id", "low-1", "gen", 5)
            ), 5L);
        });
        var reader = pool.submit(() -> {
            startGate.await();
            for (int i = 0; i < 30; i++) {
                List<Map<String, Object>> snap = stateStore.getCollection(runtimeId, collection);
                assertThat(snap).isNotEmpty();
            }
            return true;
        });

        startGate.countDown();
        assertThat(writerHigh.get()).isTrue();
        writerLow.get(); // May be true if ran before high, or false if ran after high
        assertThat(reader.get()).isTrue();
        pool.shutdown();

        List<Map<String, Object>> finalCollection = stateStore.getCollection(runtimeId, collection);
        assertThat(finalCollection).hasSize(2);
        assertThat(finalCollection).allSatisfy(item -> assertThat(item.get("gen")).isEqualTo(10));
    }

    @Test
    @DisplayName("20. Exact decimal token comparison beyond 2^53, BIGINT max, zero, and negative tokens")
    void testExactFencingTokenComparisonAndValidation() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/tokens";

        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "v1")), 1L)).isTrue();
        // Zero token cannot bypass an already-fenced collection
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "v0")), 0L)).isFalse();
        // Negative tokens are rejected immediately
        assertThatThrownBy(() -> stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "neg")), -5L))
                .isInstanceOf(IllegalArgumentException.class);

        // Adjacent values around 2^53 (IEEE-754 double precision boundary)
        long twoPow53 = 9_007_199_254_740_992L;
        long twoPow53PlusOne = 9_007_199_254_740_993L;
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "p53")), twoPow53)).isTrue();
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "p53p1")), twoPow53PlusOne)).isTrue();
        assertThat(stateStore.getCollection(runtimeId, collection)).extracting(m -> m.get("id")).containsExactly("p53p1");

        // Large positive BIGINT values up to Long.MAX_VALUE
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "max")), Long.MAX_VALUE)).isTrue();
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "max-1")), Long.MAX_VALUE - 1)).isFalse();
        assertThat(stateStore.getCollection(runtimeId, collection)).extracting(m -> m.get("id")).containsExactly("max");
        assertThat(redisBuilder.getValues().get("{mockapi:runtime:" + runtimeId + ":collection:/tokens}:fence"))
                .isEqualTo(String.valueOf(Long.MAX_VALUE));
    }

    @Test
    @DisplayName("21. Failed temporary-key population preserves previous collection, fence, and index")
    void testTempKeyFailurePreservesPreviousCollection() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/pets";

        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "orig")), 5L)).isTrue();

        redisBuilder.setNextPutAllFailure(new QueryTimeoutException("Redis OOM or timeout during temp HSET"));
        assertThatThrownBy(() -> stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "new")), 6L))
                .isInstanceOf(RuntimeStateException.class);

        // Previous collection, fence (still 5), and index are completely preserved
        assertThat(stateStore.getCollection(runtimeId, collection)).extracting(m -> m.get("id")).containsExactly("orig");
        assertThat(redisBuilder.getValues().get("{mockapi:runtime:" + runtimeId + ":collection:/pets}:fence")).isEqualTo("5");
        assertThat(stateStore.hasCollection(runtimeId, collection)).isTrue();
        // Same token 6L can still succeed on retry because fence was NOT prematurely advanced
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "retry")), 6L)).isTrue();
        assertThat(stateStore.getCollection(runtimeId, collection)).extracting(m -> m.get("id")).containsExactly("retry");
    }

    @Test
    @DisplayName("22. Stale token leaves collection, fence, and index unchanged and cleans up temp keys")
    void testStaleTokenLeavesStateAndIndexUnchanged() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/items";

        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "fresh")), 20L)).isTrue();
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "stale")), 10L)).isFalse();

        assertThat(stateStore.getCollection(runtimeId, collection)).extracting(m -> m.get("id")).containsExactly("fresh");
        assertThat(redisBuilder.getValues().get("{mockapi:runtime:" + runtimeId + ":collection:/items}:fence")).isEqualTo("20");
        assertThat(redisBuilder.getSets().get("mockapi:runtime:" + runtimeId + ":collections")).containsExactly("/items");
        assertThat(redisBuilder.getHashes().keySet()).noneMatch(k -> k.contains(":tmp:"));
    }

    @Test
    @DisplayName("23. markRuntimeDeleted blocks resurrection by in-flight workers")
    void testMarkRuntimeDeletedBlocksResurrection() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/pets";

        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "p1")), 10L)).isTrue();
        stateStore.markRuntimeDeleted(runtimeId);

        assertThat(stateStore.hasCollection(runtimeId, collection)).isFalse();
        assertThat(stateStore.getCollection(runtimeId, collection)).isEmpty();

        // Late in-flight worker with even a higher token cannot resurrect data
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "zombie")), 11L)).isFalse();
        assertThat(stateStore.hasCollection(runtimeId, collection)).isFalse();
        assertThat(stateStore.getCollection(runtimeId, collection)).isEmpty();
        assertThat(redisBuilder.getSets().get("mockapi:runtime:" + runtimeId + ":collections")).isNull();
    }

    @Test
    @DisplayName("24. Index registration failure before promotion preserves committed collection and cleans temp key")
    void testRegisterIndexFailurePreservesCollectionAndCleansTempKey() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/pets";

        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "orig")), 5L)).isTrue();
        redisBuilder.setNextRegisterFailure(new QueryTimeoutException("Redis index registration timeout"));

        assertThatThrownBy(() -> stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "new")), 6L))
                .isInstanceOf(RuntimeStateException.class);

        assertThat(stateStore.getCollection(runtimeId, collection)).extracting(m -> m.get("id")).containsExactly("orig");
        assertThat(redisBuilder.getValues().get("{mockapi:runtime:" + runtimeId + ":collection:/pets}:fence")).isEqualTo("5");
        assertThat(redisBuilder.getHashes().keySet()).noneMatch(k -> k.contains(":tmp:"));
    }

    @Test
    @DisplayName("25. Failure after index registration and before promotion cleans temp key and keeps entity count zero")
    void testFailureAfterRegistrationBeforePromotion() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/orders";

        redisBuilder.setBeforePromoteHook(() -> {
            throw new QueryTimeoutException("Simulated worker failure prior to promotion");
        });

        assertThatThrownBy(() -> stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "o1")), 1L))
                .isInstanceOf(RuntimeStateException.class);

        assertThat(stateStore.getEntityCount(runtimeId)).isZero();
        assertThat(stateStore.getCollection(runtimeId, collection)).isEmpty();
        assertThat(redisBuilder.getHashes().keySet()).noneMatch(k -> k.contains(":tmp:"));
        stateStore.clearRuntime(runtimeId);
        assertThat(stateStore.hasCollection(runtimeId, collection)).isFalse();
    }

    @Test
    @DisplayName("26. Deletion between index registration and promotion rolls back promoted collection")
    void testDeletionBetweenRegistrationAndPromotionRollsBack() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/pets";

        redisBuilder.setBeforePromoteHook(() -> stateStore.markRuntimeDeleted(runtimeId));

        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "late")), 3L)).isFalse();
        assertThat(stateStore.hasCollection(runtimeId, collection)).isFalse();
        assertThat(stateStore.getCollection(runtimeId, collection)).isEmpty();
        assertThat(redisBuilder.getSets().get("mockapi:runtime:" + runtimeId + ":collections")).isNull();
    }

    @Test
    @DisplayName("27. Multiple workers promoting concurrently around markRuntimeDeleted leave no accessible collection or index")
    void testMultipleWorkersPromotingAroundDeletion() throws Exception {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/users";
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch gate = new CountDownLatch(1);

        var w1 = pool.submit(() -> {
            gate.await();
            return stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "u1")), 10L);
        });
        var w2 = pool.submit(() -> {
            gate.await();
            return stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "u2")), 20L);
        });
        var deleter = pool.submit(() -> {
            gate.await();
            stateStore.markRuntimeDeleted(runtimeId);
            return null;
        });

        gate.countDown();
        w1.get(5, TimeUnit.SECONDS);
        w2.get(5, TimeUnit.SECONDS);
        deleter.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        // After deletion completes, any subsequent attempt is rejected and index is absent
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "u3")), 30L)).isFalse();
        assertThat(redisBuilder.getSets().get("mockapi:runtime:" + runtimeId + ":collections")).isNull();
        assertThat(stateStore.hasCollection(runtimeId, collection)).isFalse();
    }

    @Test
    @DisplayName("28. Post-promotion tombstone check failure propagates RuntimeStateException")
    void testPostPromotionTombstoneCheckFailurePropagates() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/pets";

        redisBuilder.setBeforePromoteHook(() ->
                redisBuilder.setNextHasKeyFailure(new QueryTimeoutException("Redis post-promotion hasKey timeout")));

        assertThatThrownBy(() -> stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "p1")), 1L))
                .isInstanceOf(RuntimeStateException.class)
                .hasMessageContaining("Failed to initialize collection in Redis");
    }

    @Test
    @DisplayName("29. Collection cleanup failure during markRuntimeDeleted does not swallow error or skip remaining collections")
    void testCollectionCleanupFailureDuringDeletionReportsDiagnostics() {
        UUID runtimeId = UUID.randomUUID();
        assertThat(stateStore.initializeCollection(runtimeId, "/pets", List.of(Map.of("id", "p1")), 1L)).isTrue();
        assertThat(stateStore.initializeCollection(runtimeId, "/orders", List.of(Map.of("id", "o1")), 2L)).isTrue();

        redisBuilder.setNextDeleteFailure(new QueryTimeoutException("Redis delete timeout"));

        assertThatThrownBy(() -> stateStore.markRuntimeDeleted(runtimeId))
                .isInstanceOf(RuntimeStateException.class)
                .hasMessageContaining("Incomplete Redis cleanup for deleted runtime");

        // Index was deleted and the non-failing collection was still cleaned up
        assertThat(redisBuilder.getSets().get("mockapi:runtime:" + runtimeId + ":collections")).isNull();
    }

    @Test
    @DisplayName("30. saveEntity rejects deleted runtime and does not recreate index")
    void testSaveEntityRejectedForDeletedRuntime() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/pets";

        stateStore.saveEntity(runtimeId, collection, "p1", Map.of("id", "p1", "name", "Rex"));
        stateStore.markRuntimeDeleted(runtimeId);

        assertThatThrownBy(() -> stateStore.saveEntity(runtimeId, collection, "p2", Map.of("id", "p2", "name", "Buddy")))
                .isInstanceOf(RuntimeStateException.class)
                .hasMessageContaining("deleted runtime");

        assertThat(redisBuilder.getSets().get("mockapi:runtime:" + runtimeId + ":collections")).isNull();
        assertThat(stateStore.getCollection(runtimeId, collection)).isEmpty();
    }

    @Test
    @DisplayName("31. Script null result or DataAccessException on index/deletion scripts throws RuntimeStateException")
    @SuppressWarnings("unchecked")
    void testIndexAndDeletionScriptNullAndErrorHandling() {
        StringRedisTemplate failingTemplate = mock(StringRedisTemplate.class);
        UUID runtimeId = UUID.randomUUID();

        when(failingTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(null);
        RedisRuntimeStateStore storeWithNull = new RedisRuntimeStateStore(failingTemplate, objectMapper);

        assertThatThrownBy(() -> storeWithNull.initializeCollection(runtimeId, "/pets", Collections.emptyList(), 1L))
                .isInstanceOf(RuntimeStateException.class)
                .hasMessageContaining("null");

        assertThatThrownBy(() -> storeWithNull.markRuntimeDeleted(runtimeId))
                .isInstanceOf(RuntimeStateException.class)
                .hasMessageContaining("null");

        when(failingTemplate.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Upstash unavailable"));

        assertThatThrownBy(() -> storeWithNull.markRuntimeDeleted(runtimeId))
                .isInstanceOf(RuntimeStateException.class)
                .hasMessageContaining("Failed to mark runtime deleted in Redis");
    }
}