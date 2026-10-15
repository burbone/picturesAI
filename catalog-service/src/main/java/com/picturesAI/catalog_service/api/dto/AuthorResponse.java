package com.picturesAI.catalog_service.api.dto;

import java.util.UUID;

public record AuthorResponse(
        UUID id,
        String username,
        String displayName,
        String avatarUrl,
        boolean anonymized) {
}