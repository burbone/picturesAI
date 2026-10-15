package com.picturesAI.generation_service.web.dto;

import com.picturesAI.generation_service.domain.Generation;

import java.time.Instant;
import java.util.UUID;

public record GenerationView(
        UUID id,
        UUID userId,
        String prompt,
        String negativePrompt,
        String model,
        String size,
        String tier,
        String status,
        String imageUrl,
        String thumbnailUrl,
        String provider,
        String errorMessage,
        int retryCount,
        boolean isPublic,
        UUID batchId,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant cancelledAt) {

    public static GenerationView from(Generation g) {
        return new GenerationView(
                g.getId(),
                g.getUserId(),
                g.getPrompt(),
                g.getNegativePrompt(),
                g.getModel(),
                g.getSize(),
                g.getTier().name(),
                g.getStatus().name(),
                g.getImageUrl(),
                g.getThumbnailUrl(),
                g.getProvider(),
                g.getErrorMessage(),
                g.getRetryCount(),
                g.isPublicFlag(),
                g.getBatchId(),
                g.getCreatedAt(),
                g.getStartedAt(),
                g.getCompletedAt(),
                g.getCancelledAt());
    }
}