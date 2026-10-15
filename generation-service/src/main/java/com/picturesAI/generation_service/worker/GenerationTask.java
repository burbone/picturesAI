package com.picturesAI.generation_service.worker;

import com.picturesAI.generation_service.domain.Tier;

import java.util.UUID;

public record GenerationTask(UUID generationId, Tier tier) {
}