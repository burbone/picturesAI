package com.picturesAI.billing_service.service;

import com.picturesAI.billing_service.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RedisQuotaCounterIT extends AbstractPostgresIT {

    @Autowired
    private QuotaCounter quotaCounter;

    @Test
    void incrementAndDecrement() {
        UUID userId = UUID.randomUUID();
        LocalDate date = LocalDate.now();

        assertThat(quotaCounter.increment(userId, date)).isEqualTo(1);
        assertThat(quotaCounter.increment(userId, date)).isEqualTo(2);
        assertThat(quotaCounter.increment(userId, date)).isEqualTo(3);
        assertThat(quotaCounter.decrement(userId, date)).isEqualTo(2);
        assertThat(quotaCounter.current(userId, date)).isEqualTo(2);
    }

    @Test
    void currentReturnsZeroWhenMissing() {
        assertThat(quotaCounter.current(UUID.randomUUID(), LocalDate.now())).isZero();
    }

    @Test
    void concurrentIncrementsAreAtomic() throws Exception {
        UUID userId = UUID.randomUUID();
        LocalDate date = LocalDate.now();
        int threads = 16;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        quotaCounter.increment(userId, date);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finished).isTrue();
        assertThat(quotaCounter.current(userId, date)).isEqualTo(threads * perThread);
    }
}