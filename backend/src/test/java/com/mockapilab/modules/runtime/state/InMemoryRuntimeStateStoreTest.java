package com.mockapilab.modules.runtime.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRuntimeStateStoreTest {

    private InMemoryRuntimeStateStore stateStore;

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryRuntimeStateStore();
    }

    @Test
    @DisplayName("1. Fencing rejects stale token and preserves newer generation entities")
    void testFencingRejectsStaleToken() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/pets";

        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(
                Map.of("id", "j1", "name", "Job 1")
        ), 1L)).isTrue();

        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(
                Map.of("id", "j2", "name", "Job 2")
        ), 2L)).isTrue();

        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(
                Map.of("id", "j1-stale", "name", "Stale Job 1")
        ), 1L)).isFalse();

        List<Map<String, Object>> current = stateStore.getCollection(runtimeId, collection);
        assertThat(current).hasSize(1);
        assertThat(current.get(0)).containsEntry("id", "j2").containsEntry("name", "Job 2");
    }

    @Test
    @DisplayName("2. Concurrent writers with different tokens always end with highest token's collection")
    void testConcurrentFencingWriters() throws Exception {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/orders";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);

        var low = pool.submit(() -> {
            startGate.await();
            return stateStore.initializeCollection(runtimeId, collection, List.of(
                    Map.of("id", "low-1", "gen", 3)
            ), 3L);
        });
        var high = pool.submit(() -> {
            startGate.await();
            return stateStore.initializeCollection(runtimeId, collection, List.of(
                    Map.of("id", "high-1", "gen", 9),
                    Map.of("id", "high-2", "gen", 9)
            ), 9L);
        });

        startGate.countDown();
        low.get();
        assertThat(high.get()).isTrue();
        pool.shutdown();

        List<Map<String, Object>> finalCollection = stateStore.getCollection(runtimeId, collection);
        assertThat(finalCollection).hasSize(2);
        assertThat(finalCollection).allSatisfy(item -> assertThat(item.get("gen")).isEqualTo(9));
    }

    @Test
    @DisplayName("3. markRuntimeDeleted blocks late writes while clearRuntime resets active runtime state")
    void testClearAndDeleteRuntimeSemantics() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/pets";

        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "1")), 50L)).isTrue();
        stateStore.clearRuntime(runtimeId);
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "2")), 1L)).isTrue();

        stateStore.markRuntimeDeleted(runtimeId);
        assertThat(stateStore.getCollection(runtimeId, collection)).isEmpty();
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "zombie")), 99L)).isFalse();
        assertThat(stateStore.getCollection(runtimeId, collection)).isEmpty();
    }

    @Test
    @DisplayName("4. Rejects zero after fenced write, rejects negative tokens, and supports large BIGINT tokens")
    void testZeroNegativeAndLargeBigIntTokens() {
        UUID runtimeId = UUID.randomUUID();
        String collection = "/orders";

        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "a")), 9_007_199_254_740_992L)).isTrue();
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "b")), 9_007_199_254_740_993L)).isTrue();
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "zero")), 0L)).isFalse();
        assertThatThrownBy(() -> stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "neg")), -1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stateStore.initializeCollection(runtimeId, collection, List.of(Map.of("id", "max")), Long.MAX_VALUE)).isTrue();
        assertThat(stateStore.getCollection(runtimeId, collection)).extracting(m -> m.get("id")).containsExactly("max");
    }
}
