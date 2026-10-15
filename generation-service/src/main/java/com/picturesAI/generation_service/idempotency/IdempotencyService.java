package com.picturesAI.generation_service.idempotency;

import java.time.Duration;
import java.util.UUID;

public interface IdempotencyService {

    IdempotencyResult acquire(UUID userId, String idempotencyKey, Duration ttl);

    void bind(UUID userId, String idempotencyKey, UUID generationId, Duration ttl);

    void release(UUID userId, String idempotencyKey);
}