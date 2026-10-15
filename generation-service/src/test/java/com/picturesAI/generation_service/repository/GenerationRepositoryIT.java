package com.picturesAI.generation_service.repository;

import com.picturesAI.generation_service.domain.Generation;
import com.picturesAI.generation_service.domain.GenerationStatus;
import com.picturesAI.generation_service.domain.Tier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class GenerationRepositoryIT {

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
    GenerationRepository repository;

    @Test
    void savesAndReadsAllFields() {
        Generation g = new Generation();
        g.setUserId(UUID.randomUUID());
        g.setPrompt("a cat");
        g.setNegativePrompt("blurry");
        g.setModel("sd-xl");
        g.setSize("1024x1024");
        g.setTier(Tier.PRO);
        g.setStatus(GenerationStatus.QUEUED);
        g.setIdempotencyKey("key-1");
        g.setBatchId(UUID.randomUUID());
        g.setPublicFlag(true);

        Generation saved = repository.saveAndFlush(g);

        Generation loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getPrompt()).isEqualTo("a cat");
        assertThat(loaded.getNegativePrompt()).isEqualTo("blurry");
        assertThat(loaded.getTier()).isEqualTo(Tier.PRO);
        assertThat(loaded.getStatus()).isEqualTo(GenerationStatus.QUEUED);
        assertThat(loaded.isPublicFlag()).isTrue();
        assertThat(loaded.getRetryCount()).isZero();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void appliesDefaultsForTierAndStatus() {
        Generation g = new Generation();
        g.setUserId(UUID.randomUUID());
        g.setPrompt("a dog");
        g.setModel("sd-xl");
        g.setSize("512x512");

        Generation saved = repository.saveAndFlush(g);

        assertThat(saved.getTier()).isEqualTo(Tier.FREE);
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.QUEUED);
        assertThat(saved.isPublicFlag()).isFalse();
    }

    @Test
    void rejectsDuplicateIdempotencyKeyForSameUserAndCreatedAt() {
        UUID userId = UUID.randomUUID();
        Instant created = Instant.now();
        String key = "dup-key";

        Generation first = new Generation();
        first.setUserId(userId);
        first.setPrompt("p");
        first.setModel("m");
        first.setSize("512x512");
        first.setIdempotencyKey(key);
        first.setCreatedAt(created);
        repository.saveAndFlush(first);

        Generation second = new Generation();
        second.setUserId(userId);
        second.setPrompt("p");
        second.setModel("m");
        second.setSize("512x512");
        second.setIdempotencyKey(key);
        second.setCreatedAt(created);

        assertThatThrownBy(() -> repository.saveAndFlush(second))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameIdempotencyKeyWhenItIsNull() {
        Generation a = new Generation();
        a.setUserId(UUID.randomUUID());
        a.setPrompt("p");
        a.setModel("m");
        a.setSize("512x512");

        Generation b = new Generation();
        b.setUserId(a.getUserId());
        b.setPrompt("p");
        b.setModel("m");
        b.setSize("512x512");

        repository.saveAndFlush(a);
        repository.saveAndFlush(b);

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void transitionToRetryWritesLastErrorNotErrorMessage() {
        Generation g = new Generation();
        g.setUserId(UUID.randomUUID());
        g.setPrompt("p");
        g.setModel("m");
        g.setSize("512x512");
        g.setStatus(GenerationStatus.PROCESSING);
        g.setErrorMessage("previous terminal error");
        Generation saved = repository.saveAndFlush(g);

        int updated = repository.transitionToRetry(
                saved.getId(),
                GenerationStatus.PROCESSING,
                GenerationStatus.QUEUED,
                "huggingface timeout");

        assertThat(updated).isEqualTo(1);

        Generation loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(GenerationStatus.QUEUED);
        assertThat(loaded.getLastErrorMessage()).isEqualTo("huggingface timeout");
        assertThat(loaded.getErrorMessage()).isEqualTo("previous terminal error");
        assertThat(loaded.getRetryCount()).isEqualTo(1);
    }

    @Test
    void transitionToRetryPreservesStartedAt() {
        Generation g = new Generation();
        g.setUserId(UUID.randomUUID());
        g.setPrompt("p");
        g.setModel("m");
        g.setSize("512x512");
        g.setStatus(GenerationStatus.QUEUED);
        Generation saved = repository.saveAndFlush(g);

        Instant startedAt = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MILLIS);
        repository.transitionToProcessing(
                saved.getId(),
                GenerationStatus.QUEUED,
                GenerationStatus.PROCESSING,
                startedAt);

        repository.transitionToRetry(
                saved.getId(),
                GenerationStatus.PROCESSING,
                GenerationStatus.QUEUED,
                "boom");

        Generation loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(GenerationStatus.QUEUED);
        assertThat(loaded.getStartedAt()).isNotNull();
        assertThat(loaded.getStartedAt()).isCloseTo(startedAt, within(1, ChronoUnit.SECONDS));
        assertThat(loaded.getRetryCount()).isEqualTo(1);
    }

    @Test
    void transitionToProcessingDoesNotOverwriteStartedAtOnRetry() {
        Generation g = new Generation();
        g.setUserId(UUID.randomUUID());
        g.setPrompt("p");
        g.setModel("m");
        g.setSize("512x512");
        g.setStatus(GenerationStatus.QUEUED);
        Generation saved = repository.saveAndFlush(g);

        Instant firstAttempt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        repository.transitionToProcessing(
                saved.getId(),
                GenerationStatus.QUEUED,
                GenerationStatus.PROCESSING,
                firstAttempt);

        repository.transitionToRetry(
                saved.getId(),
                GenerationStatus.PROCESSING,
                GenerationStatus.QUEUED,
                "boom");

        Instant secondAttempt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repository.transitionToProcessing(
                saved.getId(),
                GenerationStatus.QUEUED,
                GenerationStatus.PROCESSING,
                secondAttempt);

        Generation loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(loaded.getStartedAt()).isNotNull();
        assertThat(loaded.getStartedAt()).isCloseTo(firstAttempt, within(1, ChronoUnit.SECONDS));

        long secondsFromFirst = Math.abs(ChronoUnit.SECONDS.between(loaded.getStartedAt(), secondAttempt));
        assertThat(secondsFromFirst).isGreaterThanOrEqualTo(25L);
    }
}