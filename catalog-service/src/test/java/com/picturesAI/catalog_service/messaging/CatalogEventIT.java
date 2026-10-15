package com.picturesAI.catalog_service.messaging;

import com.picturesAI.catalog_service.domain.CatalogImage;
import com.picturesAI.catalog_service.domain.CatalogUser;
import com.picturesAI.catalog_service.repository.CatalogImageRepository;
import com.picturesAI.catalog_service.repository.CatalogImageTagRepository;
import com.picturesAI.catalog_service.repository.CatalogUserRepository;
import com.picturesAI.catalog_service.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest
@Testcontainers
class CatalogEventIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog_db")
            .withUsername("picturesai")
            .withPassword("picturesAI");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka-native:3.9.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.cache.type", () -> "none");
        registry.add("management.tracing.enabled", () -> "false");
        registry.add("management.otlp.tracing.endpoint", () -> "http://127.0.0.1:1");
    }

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private CatalogImageRepository images;

    @Autowired
    private CatalogImageTagRepository imageTags;

    @Autowired
    private CatalogUserRepository users;

    @Autowired
    private ProcessedEventRepository processed;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.execute("TRUNCATE TABLE catalog_image_tags, catalog_images, catalog_users, processed_events CASCADE");
    }

    private static void awaitUntil(Callable<Boolean> check) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (check.call()) {
                return;
            }
            Thread.sleep(200);
        }
        fail("timeout waiting for condition");
    }

    private void sendGenerationCompleted(UUID generationId, UUID userId, String prompt) throws Exception {
        String payload = """
                {
                  "generationId": "%s",
                  "userId": "%s",
                  "prompt": "%s",
                  "originalsBucket": "orig",
                  "originalsKey": "k1",
                  "thumbnailsBucket": "thumb",
                  "thumbnailsKey": "k2",
                  "provider": "pollinations",
                  "completedAt": "2025-01-01T00:00:00Z"
                }
                """.formatted(generationId, userId, prompt);
        kafka.send("generation.completed", generationId.toString(), payload).get();
        awaitUntil(() -> images.findById(generationId).isPresent());
    }

    @Test
    void generationCompletedCreatesRow() throws Exception {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        sendGenerationCompleted(generationId, userId, "a cat");

        CatalogImage image = images.findById(generationId).orElseThrow();
        assertThat(image.getUserId()).isEqualTo(userId);
        assertThat(image.getPrompt()).isEqualTo("a cat");
        assertThat(image.getOriginalsKey()).isEqualTo("k1");
        assertThat(processed.existsById("generation.completed:" + generationId)).isTrue();
    }

    @Test
    void tagsExtractedCreatesTags() throws Exception {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        sendGenerationCompleted(generationId, userId, "a cat");

        String payload = """
                {
                  "generationId": "%s",
                  "userId": "%s",
                  "tags": [
                    {"name": "cat", "weight": 0.60},
                    {"name": "cute", "weight": 0.40}
                  ],
                  "extractedAt": "2025-01-01T00:00:00Z"
                }
                """.formatted(generationId, userId);

        kafka.send("tags.extracted", generationId.toString(), payload).get();

        awaitUntil(() -> imageTags.findTagCounts().stream()
                .anyMatch(c -> c.getTag().equals("cat")));
        Long catCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM catalog_image_tags WHERE image_id = ? AND tag = ?",
                Long.class, generationId, "cat");
        Long cuteCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM catalog_image_tags WHERE image_id = ? AND tag = ?",
                Long.class, generationId, "cute");
        assertThat(catCount).isEqualTo(1L);
        assertThat(cuteCount).isEqualTo(1L);
        assertThat(processed.existsById("tags.extracted:" + generationId)).isTrue();
    }

    @Test
    void userCreatedCreatesRow() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID keycloakId = UUID.randomUUID();
        String payload = """
                {
                  "userId": "%s",
                  "keycloakId": "%s",
                  "username": "alice",
                  "displayName": "Alice",
                  "avatarUrl": "https://cdn/a.png",
                  "createdAt": "2025-01-01T00:00:00Z"
                }
                """.formatted(userId, keycloakId);

        kafka.send("user.created", userId.toString(), payload).get();

        awaitUntil(() -> users.findById(userId).isPresent());
        CatalogUser user = users.findById(userId).orElseThrow();
        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getDisplayName()).isEqualTo("Alice");
        assertThat(user.isAnonymized()).isFalse();
    }

    @Test
    void userDeletedAnonymizesExistingUser() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID keycloakId = UUID.randomUUID();
        CatalogUser existing = new CatalogUser();
        existing.setId(userId);
        existing.setKeycloakId(keycloakId);
        existing.setUsername("alice");
        existing.setDisplayName("Alice");
        existing.setAvatarUrl("https://cdn/a.png");
        users.save(existing);

        String payload = """
                {
                  "userId": "%s",
                  "keycloakId": "%s",
                  "deletedAt": "2025-01-01T00:00:00Z"
                }
                """.formatted(userId, keycloakId);

        kafka.send("user.deleted", userId.toString(), payload).get();

        awaitUntil(() -> users.findById(userId)
                .map(CatalogUser::isAnonymized)
                .orElse(false));
        CatalogUser reloaded = users.findById(userId).orElseThrow();
        assertThat(reloaded.getUsername()).isNull();
        assertThat(reloaded.getDisplayName()).isNull();
        assertThat(reloaded.getAvatarUrl()).isNull();
        assertThat(reloaded.getKeycloakId()).isNull();
    }

    @Test
    void duplicateGenerationEventIsIgnored() throws Exception {
        UUID generationId = UUID.randomUUID();
        String payload = """
                {
                  "generationId": "%s",
                  "prompt": "x",
                  "originalsBucket": "orig",
                  "originalsKey": "k1",
                  "thumbnailsBucket": "thumb",
                  "thumbnailsKey": "k2",
                  "completedAt": "2025-01-01T00:00:00Z"
                }
                """.formatted(generationId);

        kafka.send("generation.completed", generationId.toString(), payload).get();
        awaitUntil(() -> processed.existsById("generation.completed:" + generationId));

        kafka.send("generation.completed", generationId.toString(), payload).get();
        Thread.sleep(1000);

        Long markers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                Long.class,
                "generation.completed:" + generationId);
        assertThat(markers).isEqualTo(1L);
    }
}