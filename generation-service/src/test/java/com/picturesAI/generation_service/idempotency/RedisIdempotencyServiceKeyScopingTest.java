package com.picturesAI.generation_service.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisIdempotencyServiceKeyScopingTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private final RedisIdempotencyService service;

    {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        service = new RedisIdempotencyService(redis);
    }

    @Test
    void keyIncludesUserIdAndKey() {
        UUID userId = UUID.randomUUID();

        service.acquire(userId, "abc", Duration.ofHours(1));

        verify(valueOps).setIfAbsent(eq("idem:" + userId + ":abc"), eq("pending"), any(Duration.class));
    }
}
