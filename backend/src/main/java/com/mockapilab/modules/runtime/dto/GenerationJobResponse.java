package com.mockapilab.modules.runtime.dto;

import com.mockapilab.modules.runtime.model.GenerationJob;
import com.mockapilab.modules.runtime.model.GenerationJobStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO response representing a generation job status and metadata.
 */
public record GenerationJobResponse(
        UUID jobId,
        UUID runtimeId,
        UUID projectId,
        String collection,
        int count,
        Long requestedSeed,
        Long effectiveSeed,
        GenerationJobStatus status,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {
    public static GenerationJobResponse fromEntity(GenerationJob job) {
        return new GenerationJobResponse(
                job.getId(),
                job.getRuntime().getId(),
                job.getProjectId(),
                job.getCollectionPath(),
                job.getEntityCount(),
                job.getRequestedSeed(),
                job.getEffectiveSeed(),
                job.getStatus(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt()
        );
    }
}
