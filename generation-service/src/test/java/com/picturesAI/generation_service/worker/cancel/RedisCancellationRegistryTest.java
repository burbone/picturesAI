package com.picturesAI.generation_service.worker.cancel;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCancellationRegistryTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final SetOperations<String, String> setOps = mock(SetOperations.class);

    private final RedisCancellationRegistry registry;

    {
        when(redis.opsForSet()).thenReturn(setOps);
        registry = new RedisCancellationRegistry(redis);
    }

    @Test
    void markAddsToSet() {
        UUID id = UUID.randomUUID();
        registry.mark(id);
        verify(setOps).add(RedisCancellationRegistry.KEY, id.toString());
    }

    @Test
    void isMarkedTrueWhenMember() {
        UUID id = UUID.randomUUID();
        when(setOps.isMember(RedisCancellationRegistry.KEY, id.toString())).thenReturn(true);
        assertThat(registry.isMarked(id)).isTrue();
    }

    @Test
    void isMarkedFalseWhenNotMember() {
        UUID id = UUID.randomUUID();
        when(setOps.isMember(RedisCancellationRegistry.KEY, id.toString())).thenReturn(false);
        assertThat(registry.isMarked(id)).isFalse();
    }

    @Test
    void isMarkedFalseWhenNullReturned() {
        UUID id = UUID.randomUUID();
        when(setOps.isMember(anyString(), anyString())).thenReturn(null);
        assertThat(registry.isMarked(id)).isFalse();
    }

    @Test
    void unmarkRemovesFromSet() {
        UUID id = UUID.randomUUID();
        registry.unmark(id);
        verify(setOps).remove(eq(RedisCancellationRegistry.KEY), eq(id.toString()));
    }
}