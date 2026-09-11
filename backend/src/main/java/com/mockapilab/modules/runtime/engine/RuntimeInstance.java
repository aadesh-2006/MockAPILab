package com.mockapilab.modules.runtime.engine;

import com.mockapilab.modules.contract.model.normalized.NormalizedContract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RuntimeInstance(
        UUID runtimeId,
        UUID projectId,
        UUID contractVersionId,
        NormalizedContract contract,
        List<CompiledRoute> compiledRoutes,
        Instant startedAt
) {
}