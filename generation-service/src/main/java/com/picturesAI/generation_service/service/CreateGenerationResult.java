package com.picturesAI.generation_service.service;

import com.picturesAI.generation_service.domain.Tier;

import java.util.List;
import java.util.UUID;

public sealed interface CreateGenerationResult {

    record Created(List<UUID> jobIds, UUID batchId, Tier tier) implements CreateGenerationResult {
    }

    record Duplicate(UUID jobId) implements CreateGenerationResult {
    }

    record Conflict() implements CreateGenerationResult {
    }

    record QuotaDenied() implements CreateGenerationResult {
    }
}