package com.picturesAI.tagging_service.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.tagging_service.domain.GenerationTag;
import com.picturesAI.tagging_service.domain.OutboxEvent;
import com.picturesAI.tagging_service.repository.GenerationTagRepository;
import com.picturesAI.tagging_service.repository.OutboxRepository;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "tagging.mode=keywords",
        "management.tracing.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class GenerationCompletedConsumerIT {

    @TestConfiguration
    static class TopicsConfig {

        @Bean
        NewTopic generationCompleted() {
            return TopicBuilder.name("generation.completed").partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic userDeleted() {
            return TopicBuilder.name("user.deleted").partitions(1).replicas(1).build();
        }

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

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    GenerationTagRepository generationTags;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    ObjectMapper mapper;

    @Test
    void consumesGenerationCompletedAndExtractsTags() throws Exception {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String payload = """
                {"generationId":"%s","userId":"%s","prompt":"a cat on a sofa with a dog",
                 "imageUrl":"http://x","thumbnailUrl":"http://y","provider":"mock",
                 "completedAt":"%s"}
                """.formatted(generationId, userId, Instant.now());

        kafkaTemplate.send("generation.completed", generationId.toString(), payload)
                .get(10, TimeUnit.SECONDS);

        awaitTrue(() -> generationTags.existsByGenerationId(generationId), Duration.ofSeconds(30));

        List<GenerationTag> tags = generationTags.findByGenerationId(generationId);
        assertThat(tags).isNotEmpty();
        assertThat(tags).allSatisfy(t -> assertThat(t.getWeight())
                .isBetween(new BigDecimal("0.00"), new BigDecimal("1.00")));

        List<OutboxEvent> pending =
                outbox.findByPublishedAtIsNullOrderByCreatedAtAsc(
                        org.springframework.data.domain.PageRequest.of(0, 10));
        OutboxEvent event = pending.stream()
                .filter(e -> e.getAggregateId().equals(generationId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No outbox event for generation " + generationId));

        assertThat(event.getEventType()).isEqualTo("tags.extracted");

        JsonNode node = mapper.readTree(event.getPayload());
        assertThat(node.path("generationId").asText()).isEqualTo(generationId.toString());
        assertThat(node.path("userId").asText()).isEqualTo(userId.toString());
        assertThat(node.path("tags").isArray()).isTrue();
        assertThat(node.path("tags")).anySatisfy(t ->
                assertThat(t.path("name").asText()).isEqualTo("cat"));
        assertThat(node.path("tags")).anySatisfy(t ->
                assertThat(t.path("name").asText()).isEqualTo("dog"));
        assertThat(node.path("extractedAt").asText()).isNotBlank();
    }

    @Test
    void duplicateEventIsIdempotent() throws Exception {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String payload = """
                {"generationId":"%s","userId":"%s","prompt":"a cat","completedAt":"%s"}
                """.formatted(generationId, userId, Instant.now());

        kafkaTemplate.send("generation.completed", generationId.toString(), payload).get();
        awaitTrue(() -> generationTags.existsByGenerationId(generationId), Duration.ofSeconds(30));
        int firstCount = generationTags.findByGenerationId(generationId).size();

        kafkaTemplate.send("generation.completed", generationId.toString(), payload).get();
        Thread.sleep(2000);

        int secondCount = generationTags.findByGenerationId(generationId).size();
        assertThat(secondCount).isEqualTo(firstCount);
    }

    @Test
    void malformedPayloadIsSkipped() throws Exception {
        kafkaTemplate.send("generation.completed", "bad-key", "not-json").get();
        Thread.sleep(1000);
    }

    @Test
    void missingGenerationIdIsSkipped() throws Exception {
        kafkaTemplate.send("generation.completed", "no-id", "{\"userId\":\"x\"}").get();
        Thread.sleep(1000);
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Condition not met within " + timeout);
    }
}