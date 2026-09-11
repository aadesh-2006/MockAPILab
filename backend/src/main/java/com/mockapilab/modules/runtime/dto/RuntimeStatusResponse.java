package com.mockapilab.modules.runtime.dto;

import com.mockapilab.modules.runtime.model.MockRuntimeStatus;

import java.time.Instant;
import java.util.UUID;

public record RuntimeStatusResponse(
        UUID id,
        MockRuntimeStatus status,
        String mockBaseUrl,
        int endpointsCount,
        int storedEntitiesCount,
        int collectionsCount,
        Instant startedAt,
        long uptimeSeconds
) {
}