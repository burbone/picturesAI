package com.picturesAI.catalog_service.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserCreatedEvent(
        UUID userId,
        UUID keycloakId,
        String username,
        String displayName,
        String avatarUrl,
        Instant createdAt) {
}