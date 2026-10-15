package com.picturesAI.api_gateway.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@Primary
public class TierAwareRedisRateLimiter implements RateLimiter<TierAwareRedisRateLimiter.Config> {

    private static final String SCRIPT = """
            local tokens_key = KEYS[1]
            local timestamp_key = KEYS[2]
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local rate_per_second = rate / 60
            local fill_time = capacity / rate_per_second
            local ttl = math.floor(fill_time * 2)
            if ttl <= 0 then ttl = 1 end
            local last_tokens = tonumber(redis.call("get", tokens_key))
            if last_tokens == nil then last_tokens = capacity end
            local last_refreshed = tonumber(redis.call("get", timestamp_key))
            if last_refreshed == nil then last_refreshed = 0 end
            local delta = math.max(0, now - last_refreshed)
            local filled_tokens = math.min(capacity, last_tokens + delta * rate_per_second)
            local allowed = filled_tokens >= requested
            local new_tokens = filled_tokens
            if allowed then new_tokens = filled_tokens - requested end
            redis.call("setex", tokens_key, ttl, new_tokens)
            redis.call("setex", timestamp_key, ttl, now)
            return { allowed and 1 or 0, new_tokens }
            """;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<List> script;
    private final int freeRate;
    private final int proRate;
    private final int billingRate;

    public TierAwareRedisRateLimiter(ReactiveStringRedisTemplate redisTemplate,
                                     @Value("${app.rate-limit.free:10}") int freeRate,
                                     @Value("${app.rate-limit.pro:100}") int proRate,
                                     @Value("${app.rate-limit.billing:30}") int billingRate) {
        this.redisTemplate = redisTemplate;
        this.script = RedisScript.of(SCRIPT, List.class);
        this.freeRate = freeRate;
        this.proRate = proRate;
        this.billingRate = billingRate;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        int rate = rateFor(routeId, id);
        long now = Instant.now().getEpochSecond();
        String tokensKey = "request_rate_limiter." + routeId + "." + id + ".tokens";
        String timestampKey = "request_rate_limiter." + routeId + "." + id + ".timestamp";
        List<String> keys = List.of(tokensKey, timestampKey);
        List<String> args = List.of(
                String.valueOf(rate),
                String.valueOf(rate),
                String.valueOf(now),
                "1");
        int retryAfter = (int) Math.max(1, Math.ceil(60.0 / rate));
        return redisTemplate.execute(script, keys, args)
                .next()
                .map(raw -> {
                    List<?> list = (List<?>) raw;
                    long allowedFlag = ((Number) list.get(0)).longValue();
                    long remaining = ((Number) list.get(1)).longValue();
                    return new Response(allowedFlag == 1,
                            Map.of("X-RateLimit-Replenish-Rate", String.valueOf(rate),
                                    "X-RateLimit-Burst-Capacity", String.valueOf(rate),
                                    "X-RateLimit-Remaining", String.valueOf(remaining),
                                    "Retry-After", String.valueOf(retryAfter)));
                });
    }

    @Override
    public Class<Config> getConfigClass() {
        return Config.class;
    }

    @Override
    public Config newConfig() {
        return new Config();
    }

    @Override
    public Map<String, Config> getConfig() {
        return Map.of();
    }

    int rateFor(String routeId, String id) {
        if (routeId != null && routeId.startsWith("billing")) {
            return billingRate;
        }
        return id != null && id.endsWith(":pro") ? proRate : freeRate;
    }

    public static class Config {
    }
}