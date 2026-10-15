package com.picturesAI.user_service;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
public abstract class AbstractKafkaTest {

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("users_db")
                    .withUsername("picturesai")
                    .withPassword("picturesAI");

    public static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("outbox.publisher.enabled", () -> "true");
        registry.add("outbox.publisher.initial-delay-ms", () -> "3600000");
        registry.add("outbox.publisher.poll-interval-ms", () -> "3600000");
        registry.add("user.deletion.enabled", () -> "true");
        registry.add("user.deletion.initial-delay-ms", () -> "3600000");
        registry.add("user.deletion.poll-interval-ms", () -> "3600000");
    }
}