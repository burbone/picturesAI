package com.picturesAI.tagging_service;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class FlywayMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tags_db")
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
        assertThat(tables).contains(
                "tag_vocabulary",
                "generation_tags",
                "tag_stats",
                "outbox",
                "flyway_schema_history");
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
                "idx_generation_tags_tag_id",
                "idx_generation_tags_tag_weight",
                "idx_tag_vocabulary_aliases",
                "idx_outbox_unpublished");
    }

    @Test
    void seedVocabularyLoadedWithExpectedCounts() throws Exception {
        Map<String, Integer> byCategory = new HashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT category, COUNT(*) FROM tag_vocabulary GROUP BY category");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byCategory.put(rs.getString(1), rs.getInt(2));
            }
        }
        assertThat(byCategory).containsEntry("subject", 16);
        assertThat(byCategory).containsEntry("style", 11);
        assertThat(byCategory).containsEntry("color", 10);
        assertThat(byCategory).containsEntry("mood", 7);
        assertThat(byCategory).containsEntry("composition", 5);
        assertThat(byCategory.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(49);
    }

    @Test
    void tagStatsSeededForEveryVocabularyRow() throws Exception {
        int vocabularyCount;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM tag_vocabulary");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            vocabularyCount = rs.getInt(1);
        }

        int statsCount;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM tag_stats");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            statsCount = rs.getInt(1);
        }

        assertThat(statsCount).isEqualTo(vocabularyCount);
    }

    @Test
    void aliasesAreStoredAsArrayWithCyrillic() throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT aliases FROM tag_vocabulary WHERE name = 'cat'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            java.sql.Array arr = rs.getArray(1);
            String[] aliases = (String[]) arr.getArray();
            assertThat(aliases).contains("кот", "кошка", "kitten", "котёнок");
        }
    }
}