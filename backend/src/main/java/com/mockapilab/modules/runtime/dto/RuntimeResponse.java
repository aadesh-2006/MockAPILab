package com.mockapilab.modules.runtime.dto;

import com.mockapilab.modules.runtime.model.MockRuntimeStatus;

import java.time.Instant;
import java.util.UUID;

public record RuntimeResponse(
        UUID id,
        UUID projectId,
        UUID contractId,
        UUID contractVersionId,
        int versionNumber,
        String name,
        MockRuntimeStatus status,
        String mockBaseUrl,
        int endpointsCount,
        int storedEntitiesCount,
        Instant createdAt,
        Instant startedAt,
        Instant stoppedAt
) {
}