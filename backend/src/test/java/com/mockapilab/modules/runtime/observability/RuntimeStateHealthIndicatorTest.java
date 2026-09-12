package com.mockapilab.modules.runtime.observability;

import com.mockapilab.modules.runtime.state.RuntimeStateException;
import com.mockapilab.modules.runtime.state.RuntimeStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RuntimeStateHealthIndicatorTest {

    @Test
    @DisplayName("Reports UP when runtime state store health check succeeds")
    void testHealthUpWhenStoreAvailable() throws Exception {
        RuntimeStateStore stateStore = mock(RuntimeStateStore.class);
        doNothing().when(stateStore).checkHealth();

        RuntimeStateHealthIndicator indicator = new RuntimeStateHealthIndicator(stateStore, "redis");
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("stateStoreType", "redis");
        assertThat(health.getDetails()).containsEntry("status", "OPERATIONAL");
        verify(stateStore).checkHealth();
    }

    @Test
    @DisplayName("Reports DOWN when runtime state store health check fails (e.g. Redis unreachable)")
    void testHealthDownWhenStoreFails() throws Exception {
        RuntimeStateStore stateStore = mock(RuntimeStateStore.class);
        doThrow(new RuntimeStateException("Redis state store is unreachable: Connection refused"))
                .when(stateStore).checkHealth();

        RuntimeStateHealthIndicator indicator = new RuntimeStateHealthIndicator(stateStore, "redis");
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("stateStoreType", "redis");
        assertThat(health.getDetails()).containsKey("error");
        assertThat(String.valueOf(health.getDetails().get("error"))).contains("Redis state store is unreachable");
        verify(stateStore).checkHealth();
    }
}