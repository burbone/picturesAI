package com.picturesAI.tagging_service.kafka;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@SpringBootTest(properties = {
        "tagging.mode=keywords",
        "management.tracing.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class UserDeletedConsumerIT {

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

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void userDeletedIsAcknowledgedWithoutAction() throws Exception {
        String payload = "{\"userId\":\"" + UUID.randomUUID() + "\"}";

        kafkaTemplate.send("user.deleted", UUID.randomUUID().toString(), payload)
                .get(10, TimeUnit.SECONDS);

        Thread.sleep(1500);
    }
}