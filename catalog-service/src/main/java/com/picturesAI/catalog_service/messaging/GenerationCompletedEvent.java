package com.picturesAI.catalog_service.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerationCompletedEvent(
        UUID generationId,
        UUID userId,
        String prompt,
        String originalsBucket,
        String originalsKey,
        String thumbnailsBucket,
        String thumbnailsKey,
        String provider,
        Instant completedAt) {
}