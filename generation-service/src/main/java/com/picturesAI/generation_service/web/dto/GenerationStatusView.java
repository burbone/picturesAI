package com.picturesAI.generation_service.web.dto;

import com.picturesAI.generation_service.domain.Generation;

import java.time.Instant;
import java.util.UUID;

public record GenerationStatusView(
        UUID id,
        String status,
        String tier,
        String imageUrl,
        String thumbnailUrl,
        String errorMessage,
        Instant createdAt,
        Instant completedAt,
        Instant cancelledAt) {

    public static GenerationStatusView from(Generation g) {
        return new GenerationStatusView(
                g.getId(),
                g.getStatus().name(),
                g.getTier().name(),
                g.getImageUrl(),
                g.getThumbnailUrl(),
                g.getErrorMessage(),
                g.getCreatedAt(),
                g.getCompletedAt(),
                g.getCancelledAt());
    }
}