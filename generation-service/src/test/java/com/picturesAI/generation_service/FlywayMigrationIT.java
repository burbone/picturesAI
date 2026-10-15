package com.picturesAI.generation_service;

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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class FlywayMigrationIT {

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
    DataSource dataSource;

    @Test
    void tablesCreated() throws Exception {
        Set<String> tables = new HashSet<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        assertThat(tables).contains("generations", "outbox", "flyway_schema_history");
    }

    @Test
    void indexesCreated() throws Exception {
        Set<String> indexes = new HashSet<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        assertThat(indexes).contains(
                "idx_generations_user_created",
                "idx_generations_queue",
                "idx_generations_idempotency",
                "idx_generations_batch",
                "idx_outbox_unpublished");
    }
}