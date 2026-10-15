package com.picturesAI.tagging_service.outbox;

import com.picturesAI.tagging_service.domain.OutboxEvent;
import com.picturesAI.tagging_service.repository.OutboxRepository;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "tagging.mode=keywords",
        "tagging.outbox.fixed-delay-ms=200",
        "tagging.outbox.send-timeout-ms=5000",
        "management.tracing.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class OutboxPublisherIT {

    @TestConfiguration
    static class TopicsConfig {

        @Bean
        NewTopic tagsExtracted() {
            return TopicBuilder.name("tags.extracted").partitions(1).replicas(1).build();
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tags_db")
            .withUsername("picturesai")
            .withPassword("picturesAI");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka-native:3.9.0"))
            .withEnv("KAFKA_LISTENERS",
                    "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094");

    @Autowired
    OutboxRepository outbox;

    @Test
    void publishesPendingEventAndMarksPublished() {
        OutboxEvent e = new OutboxEvent();
        e.setAggregateId(UUID.randomUUID());
        e.setEventType("tags.extracted");
        e.setPayload("{\"generationId\":\"x\",\"tags\":[]}");
        outbox.saveAndFlush(e);

        awaitTrue(() -> outbox.findById(e.getId())
                        .map(x -> x.getPublishedAt() != null).orElse(false),
                Duration.ofSeconds(20));

        OutboxEvent reloaded = outbox.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getPublishedAt()).isNotNull();
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("Condition not met within " + timeout);
    }
}