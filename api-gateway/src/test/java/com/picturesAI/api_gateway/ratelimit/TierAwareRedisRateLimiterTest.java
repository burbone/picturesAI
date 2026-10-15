package com.picturesAI.api_gateway.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TierAwareRedisRateLimiterTest {

    private final TierAwareRedisRateLimiter limiter =
            new TierAwareRedisRateLimiter(null, 10, 100, 30);

    @Test
    void freeUserGetsFreeRate() {
        assertThat(limiter.rateFor("generation-service", "user-1:free")).isEqualTo(10);
    }

    @Test
    void proUserGetsProRate() {
        assertThat(limiter.rateFor("generation-service", "user-1:pro")).isEqualTo(100);
    }

    @Test
    void billingRouteGetsBillingRate() {
        assertThat(limiter.rateFor("billing-service", "user-1:free")).isEqualTo(30);
    }

    @Test
    void billingRouteProStillBillingRate() {
        assertThat(limiter.rateFor("billing-service", "user-1:pro")).isEqualTo(30);
    }
}