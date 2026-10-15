package com.picturesAI.user_service.dto;

import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Size(max = 100) String displayName,
        @Size(max = 2000) String avatarUrl,
        @Size(max = 2000) String bio,
        Boolean isPublic
) {
}