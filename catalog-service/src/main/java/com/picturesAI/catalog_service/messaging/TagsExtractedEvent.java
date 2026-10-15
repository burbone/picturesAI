package com.picturesAI.catalog_service.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TagsExtractedEvent(
        UUID generationId,
        UUID userId,
        List<Tag> tags,
        Instant extractedAt) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tag(String name, BigDecimal weight) {
    }
}