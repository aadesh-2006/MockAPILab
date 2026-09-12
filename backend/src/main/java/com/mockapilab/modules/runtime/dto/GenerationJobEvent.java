package com.mockapilab.modules.runtime.dto;

import java.util.UUID;

/**
 * Event DTO published to Kafka topic (e.g. mockapi.generation.jobs) to request asynchronous collection generation.
 */
public record GenerationJobEvent(
        UUID jobId,
        UUID runtimeId,
        UUID projectId,
        String collection,
        int count,
        Long requestedSeed
) {
}
