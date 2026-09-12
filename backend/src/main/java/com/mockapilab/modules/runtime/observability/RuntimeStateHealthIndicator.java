package com.mockapilab.modules.runtime.observability;

import com.mockapilab.modules.runtime.state.RuntimeStateStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Spring Boot Actuator HealthIndicator reporting the operational status
 * and backend implementation of the RuntimeStateStore (Redis / In-Memory).
 */
@Component("runtimeStateStore")
public class RuntimeStateHealthIndicator implements HealthIndicator {

    private final RuntimeStateStore runtimeStateStore;
    private final String configuredStoreType;

    public RuntimeStateHealthIndicator(
            RuntimeStateStore runtimeStateStore,
            @Value("${mockapilab.runtime.state-store:redis}") String configuredStoreType
    ) {
        this.runtimeStateStore = runtimeStateStore;
        this.configuredStoreType = configuredStoreType;
    }

    @Override
    public Health health() {
        String storeClassName = runtimeStateStore.getClass().getSimpleName();
        try {
            runtimeStateStore.checkHealth();
            return Health.up()
                    .withDetail("stateStoreType", configuredStoreType)
                    .withDetail("implementation", storeClassName)
                    .withDetail("status", "OPERATIONAL")
                    .build();
        } catch (Exception ex) {
            return Health.down()
                    .withDetail("stateStoreType", configuredStoreType)
                    .withDetail("implementation", storeClassName)
                    .withDetail("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())
                    .build();
        }
    }
}