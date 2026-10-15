package com.picturesAI.generation_service.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class RedisIdempotencyService implements IdempotencyService {

    private static final String PENDING = "pending";

    private final StringRedisTemplate redis;

    public RedisIdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public IdempotencyResult acquire(UUID userId, String idempotencyKey, Duration ttl) {
        String key = key(userId, idempotencyKey);
        Boolean inserted = redis.opsForValue().setIfAbsent(key, PENDING, ttl);
        if (Boolean.TRUE.equals(inserted)) {
            return new IdempotencyResult.New(UUID.randomUUID());
        }
        String value = redis.opsForValue().get(key);
        if (value == null || PENDING.equals(value)) {
            return new IdempotencyResult.Conflict();
        }
        return new IdempotencyResult.Duplicate(UUID.fromString(value));
    }

    @Override
    public void bind(UUID userId, String idempotencyKey, UUID generationId, Duration ttl) {
        redis.opsForValue().set(key(userId, idempotencyKey), generationId.toString(), ttl);
    }

    @Override
    public void release(UUID userId, String idempotencyKey) {
        redis.delete(key(userId, idempotencyKey));
    }

    private String key(UUID userId, String idempotencyKey) {
        return "idem:" + userId + ":" + idempotencyKey;
    }
}