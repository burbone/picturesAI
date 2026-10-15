package com.picturesAI.generation_service.repository;

import com.picturesAI.generation_service.domain.Generation;
import com.picturesAI.generation_service.domain.GenerationStatus;
import com.picturesAI.generation_service.domain.Tier;
import jakarta.persistence.EntityManager;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class GenerationAnonymizeIT {

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

    @Autowired
    EntityManager em;

    @Test
    void anonymizeClearsUserIdAndPromptForOwnerOnly() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Generation g1 = newGeneration(owner, "a cat");
        Generation g2 = newGeneration(owner, "a dog");
        Generation g3 = newGeneration(other, "a bird");
        repository.saveAndFlush(g1);
        repository.saveAndFlush(g2);
        repository.saveAndFlush(g3);

        int updated = repository.anonymizeByUserId(owner);
        em.flush();
        em.clear();

        assertThat(updated).isEqualTo(2);

        Generation r1 = repository.findById(g1.getId()).orElseThrow();
        Generation r2 = repository.findById(g2.getId()).orElseThrow();
        Generation r3 = repository.findById(g3.getId()).orElseThrow();

        assertThat(r1.getUserId()).isNull();
        assertThat(r1.getPrompt()).isEqualTo("[deleted]");
        assertThat(r2.getUserId()).isNull();
        assertThat(r2.getPrompt()).isEqualTo("[deleted]");
        assertThat(r3.getUserId()).isEqualTo(other);
        assertThat(r3.getPrompt()).isEqualTo("a bird");
    }

    @Test
    void anonymizeIsIdempotent() {
        UUID owner = UUID.randomUUID();
        Generation g = newGeneration(owner, "p");
        repository.saveAndFlush(g);

        assertThat(repository.anonymizeByUserId(owner)).isEqualTo(1);
        em.flush();
        em.clear();

        assertThat(repository.anonymizeByUserId(owner)).isZero();
        em.flush();
    }

    @Test
    void anonymizeForUnknownUserUpdatesNothing() {
        assertThat(repository.anonymizeByUserId(UUID.randomUUID())).isZero();
        em.flush();
    }

    private Generation newGeneration(UUID userId, String prompt) {
        Generation g = new Generation();
        g.setId(UUID.randomUUID());
        g.setUserId(userId);
        g.setPrompt(prompt);
        g.setModel("sd-xl");
        g.setSize("512x512");
        g.setTier(Tier.FREE);
        g.setStatus(GenerationStatus.COMPLETED);
        return g;
    }
}