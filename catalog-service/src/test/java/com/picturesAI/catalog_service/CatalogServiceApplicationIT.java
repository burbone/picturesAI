package com.picturesAI.catalog_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class CatalogServiceApplicationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog_db")
            .withUsername("picturesai")
            .withPassword("picturesAI");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.cache.type", () -> "none");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
        registry.add("management.tracing.enabled", () -> "false");
        registry.add("management.otlp.tracing.endpoint", () -> "http://127.0.0.1:1");
    }

    @Test
    void contextLoads() {
    }
}