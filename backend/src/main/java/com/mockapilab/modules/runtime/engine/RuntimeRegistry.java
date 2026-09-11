package com.mockapilab.modules.runtime.engine;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory registry of active MockRuntime execution instances.
 */
@Component
public class RuntimeRegistry {

    private final Map<UUID, RuntimeInstance> activeRuntimes = new ConcurrentHashMap<>();

    public void register(RuntimeInstance instance) {
        activeRuntimes.put(instance.runtimeId(), instance);
    }

    public void unregister(UUID runtimeId) {
        activeRuntimes.remove(runtimeId);
    }

    public Optional<RuntimeInstance> get(UUID runtimeId) {
        return Optional.ofNullable(activeRuntimes.get(runtimeId));
    }

    public boolean isRunning(UUID runtimeId) {
        return activeRuntimes.containsKey(runtimeId);
    }

    public int getActiveCount() {
        return activeRuntimes.size();
    }

    public void clear() {
        activeRuntimes.clear();
    }
}