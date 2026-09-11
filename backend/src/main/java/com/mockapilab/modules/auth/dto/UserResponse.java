package com.mockapilab.modules.auth.dto;

import com.mockapilab.modules.auth.model.User;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String displayName,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserResponse fromEntity(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
