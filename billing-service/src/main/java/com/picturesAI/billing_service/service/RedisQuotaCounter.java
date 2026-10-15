package com.picturesAI.billing_service.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class RedisQuotaCounter implements QuotaCounter {

    private static final String KEY_PREFIX = "quota:";

    private final StringRedisTemplate redis;

    public RedisQuotaCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(UUID userId, LocalDate date) {
        String key = key(userId, date);
        Long value = redis.opsForValue().increment(key);
        if (value != null && value == 1L) {
            redis.expire(key, ttlUntilEndOfDay(date));
        }
        return value == null ? 0L : value;
    }

    @Override
    public long decrement(UUID userId, LocalDate date) {
        Long value = redis.opsForValue().decrement(key(userId, date));
        return value == null ? 0L : value;
    }

    @Override
    public long current(UUID userId, LocalDate date) {
        String value = redis.opsForValue().get(key(userId, date));
        return value == null ? 0L : Long.parseLong(value);
    }

    private String key(UUID userId, LocalDate date) {
        return KEY_PREFIX + userId + ":" + date;
    }

    private Duration ttlUntilEndOfDay(LocalDate date) {
        Instant endOfDay = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Duration ttl = Duration.between(Instant.now(), endOfDay);
        return ttl.isNegative() ? Duration.ofMinutes(1) : ttl;
    }
}