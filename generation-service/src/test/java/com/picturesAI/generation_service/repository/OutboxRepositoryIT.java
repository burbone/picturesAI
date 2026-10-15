package com.picturesAI.generation_service.repository;

import com.picturesAI.generation_service.domain.OutboxEvent;
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
class OutboxRepositoryIT {

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
    OutboxRepository repository;

    @Test
    void storesJsonbPayloadAndFindsUnpublished() {
        OutboxEvent e = new OutboxEvent();
        e.setAggregateId(UUID.randomUUID());
        e.setEventType("generation.completed");
        e.setPayload("{\"k\":\"v\"}");
        repository.saveAndFlush(e);

        List<OutboxEvent> unpublished =
                repository.findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, 10));

        assertThat(unpublished).hasSize(1);
        assertThat(unpublished.get(0).getPayload()).contains("\"k\"");
        assertThat(unpublished.get(0).getPublishedAt()).isNull();
    }

    @Test
    void excludesPublishedFromResult() {
        OutboxEvent e = new OutboxEvent();
        e.setAggregateId(UUID.randomUUID());
        e.setEventType("generation.failed");
        e.setPayload("{}");
        e.setPublishedAt(Instant.now());
        repository.saveAndFlush(e);

        List<OutboxEvent> unpublished =
                repository.findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, 10));

        assertThat(unpublished).isEmpty();
    }
}