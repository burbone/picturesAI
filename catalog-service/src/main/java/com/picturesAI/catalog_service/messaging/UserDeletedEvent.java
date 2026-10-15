package com.picturesAI.catalog_service.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserDeletedEvent(
        UUID userId,
        UUID keycloakId,
        Instant deletedAt) {
}