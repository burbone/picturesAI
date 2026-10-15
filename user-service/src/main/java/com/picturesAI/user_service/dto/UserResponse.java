package com.picturesAI.user_service.dto;

import com.picturesAI.user_service.domain.Plan;
import com.picturesAI.user_service.domain.Status;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        UUID keycloakId,
        String username,
        String displayName,
        String avatarUrl,
        String bio,
        boolean isPublic,
        Plan plan,
        Status status,
        Instant createdAt,
        Instant updatedAt
) {
}