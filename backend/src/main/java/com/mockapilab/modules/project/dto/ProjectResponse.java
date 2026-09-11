package com.mockapilab.modules.project.dto;

import com.mockapilab.modules.project.model.Project;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String description,
        UUID ownerId,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProjectResponse fromEntity(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getOwner().getId(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
