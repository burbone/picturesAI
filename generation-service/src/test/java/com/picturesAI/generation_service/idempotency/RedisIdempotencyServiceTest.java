package com.picturesAI.generation_service.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisIdempotencyServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private final RedisIdempotencyService service;

    {
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new RedisIdempotencyService(redis);
    }

    @Test
    void acquireReturnsNewWhenKeyAbsent() {
        UUID userId = UUID.randomUUID();
        when(valueOps.setIfAbsent(anyString(), eq("pending"), any(Duration.class))).thenReturn(true);

        IdempotencyResult result = service.acquire(userId, "k", Duration.ofHours(1));

        assertThat(result).isInstanceOf(IdempotencyResult.New.class);
    }

    @Test
    void acquireReturnsDuplicateWhenBound() {
        UUID userId = UUID.randomUUID();
        UUID bound = UUID.randomUUID();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn(bound.toString());

        IdempotencyResult result = service.acquire(userId, "k", Duration.ofHours(1));

        assertThat(result).isEqualTo(new IdempotencyResult.Duplicate(bound));
    }

    @Test
    void acquireReturnsConflictWhenPending() {
        UUID userId = UUID.randomUUID();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn("pending");

        IdempotencyResult result = service.acquire(userId, "k", Duration.ofHours(1));

        assertThat(result).isInstanceOf(IdempotencyResult.Conflict.class);
    }

    @Test
    void acquireReturnsConflictWhenValueExpiredBetweenSetIfAbsentAndGet() {
        UUID userId = UUID.randomUUID();
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn(null);

        IdempotencyResult result = service.acquire(userId, "k", Duration.ofHours(1));

        assertThat(result).isInstanceOf(IdempotencyResult.Conflict.class);
    }

    @Test
    void bindWritesGenerationIdWithTtl() {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();

        service.bind(userId, "k", generationId, Duration.ofHours(1));

        verify(valueOps).set(eq("idem:" + userId + ":k"), eq(generationId.toString()), any(Duration.class));
    }

    @Test
    void releaseDeletesKey() {
        UUID userId = UUID.randomUUID();

        service.release(userId, "k");

        verify(redis).delete("idem:" + userId + ":k");
    }
}