package com.picturesAI.generation_service.worker.retry;

import com.picturesAI.generation_service.domain.Tier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class RetryQueueRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("generations_db")
            .withUsername("picturesai")
            .withPassword("picturesAI");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    RetryQueueRepository repository;

    @Test
    void findsOnlyReadyUnprocessed() {
        Instant now = Instant.now();
        RetryQueueEntry ready = entry(now.minusSeconds(5));
        RetryQueueEntry future = entry(now.plusSeconds(60));
        RetryQueueEntry processed = entry(now.minusSeconds(5));
        processed.setProcessedAt(now.minusSeconds(1));
        repository.saveAll(List.of(ready, future, processed));

        List<RetryQueueEntry> found = repository
                .findByProcessedAtIsNullAndScheduledAtBeforeOrderByScheduledAtAsc(
                        now, PageRequest.of(0, 100));

        assertThat(found).extracting(RetryQueueEntry::getId)
                .containsExactly(ready.getId());
    }

    @Test
    void markProcessedRemovesFromPending() {
        RetryQueueEntry e = entry(Instant.now().minusSeconds(5));
        repository.saveAndFlush(e);

        int updated = repository.markProcessed(e.getId(), Instant.now());

        assertThat(updated).isEqualTo(1);
        assertThat(repository.countByProcessedAtIsNull()).isZero();
    }

    @Test
    void markProcessedIsIdempotent() {
        RetryQueueEntry e = entry(Instant.now().minusSeconds(5));
        repository.saveAndFlush(e);

        assertThat(repository.markProcessed(e.getId(), Instant.now())).isEqualTo(1);
        assertThat(repository.markProcessed(e.getId(), Instant.now())).isZero();
    }

    private RetryQueueEntry entry(Instant scheduledAt) {
        RetryQueueEntry e = new RetryQueueEntry();
        e.setGenerationId(UUID.randomUUID());
        e.setUserId(UUID.randomUUID());
        e.setTier(Tier.FREE);
        e.setRetryCount(1);
        e.setScheduledAt(scheduledAt);
        return e;
    }
}