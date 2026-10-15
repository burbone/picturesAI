package com.picturesAI.generation_service.idempotency;

import java.util.UUID;

public sealed interface IdempotencyResult {

    record New(UUID generationId) implements IdempotencyResult {
    }

    record Duplicate(UUID generationId) implements IdempotencyResult {
    }

    record Conflict() implements IdempotencyResult {
    }
}
