package com.picturesAI.generation_service.worker.cancel;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RedisCancellationRegistry implements CancellationRegistry {

    static final String KEY = "cancelled:jobs";

    private final StringRedisTemplate redis;

    public RedisCancellationRegistry(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void mark(UUID generationId) {
        redis.opsForSet().add(KEY, generationId.toString());
    }

    @Override
    public boolean isMarked(UUID generationId) {
        Boolean member = redis.opsForSet().isMember(KEY, generationId.toString());
        return Boolean.TRUE.equals(member);
    }

    @Override
    public void unmark(UUID generationId) {
        redis.opsForSet().remove(KEY, generationId.toString());
    }
}