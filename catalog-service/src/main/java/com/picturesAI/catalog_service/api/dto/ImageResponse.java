package com.picturesAI.catalog_service.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ImageResponse(
        UUID id,
        UUID userId,
        String prompt,
        String imageUrl,
        String thumbnailUrl,
        String provider,
        Instant completedAt,
        List<String> tags,
        AuthorResponse author) {
}