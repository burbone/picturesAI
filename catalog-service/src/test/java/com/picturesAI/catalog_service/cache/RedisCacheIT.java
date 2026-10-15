package com.picturesAI.catalog_service.cache;

import com.picturesAI.catalog_service.api.CursorPage;
import com.picturesAI.catalog_service.api.dto.ImageResponse;
import com.picturesAI.catalog_service.api.dto.TagCountResponse;
import com.picturesAI.catalog_service.domain.CatalogImage;
import com.picturesAI.catalog_service.domain.CatalogImageTag;
import com.picturesAI.catalog_service.messaging.GenerationCompletedEvent;
import com.picturesAI.catalog_service.messaging.TagsExtractedEvent;
import com.picturesAI.catalog_service.repository.CatalogImageRepository;
import com.picturesAI.catalog_service.repository.CatalogImageTagRepository;
import com.picturesAI.catalog_service.service.CatalogQueryService;
import com.picturesAI.catalog_service.service.CatalogWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RedisCacheIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog_db")
            .withUsername("picturesai")
            .withPassword("picturesAI");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.cache.type", () -> "redis");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
        registry.add("management.tracing.enabled", () -> "false");
        registry.add("management.otlp.tracing.endpoint", () -> "http://127.0.0.1:1");
    }

    @Autowired
    private CatalogQueryService query;

    @Autowired
    private CatalogWriteService writer;

    @Autowired
    private CatalogImageRepository images;

    @Autowired
    private CatalogImageTagRepository imageTags;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.execute("TRUNCATE TABLE catalog_image_tags, catalog_images, catalog_users, processed_events CASCADE");
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    private CatalogImage image(String prompt, Instant completedAt) {
        CatalogImage img = new CatalogImage();
        img.setId(UUID.randomUUID());
        img.setPrompt(prompt);
        img.setOriginalsBucket("orig");
        img.setOriginalsKey("k/" + img.getId());
        img.setThumbnailsBucket("thumb");
        img.setThumbnailsKey("k/" + img.getId());
        img.setProvider("pollinations");
        img.setCompletedAt(completedAt);
        return img;
    }

    @Test
    void feedFirstPageCachedOnSecondCall() {
        images.save(image("first", Instant.parse("2025-01-01T00:00:00Z")));

        CursorPage<ImageResponse> first = query.getFeed(null, 20);
        assertThat(first.items()).hasSize(1);

        images.save(image("second", Instant.parse("2025-01-02T00:00:00Z")));

        CursorPage<ImageResponse> second = query.getFeed(null, 20);
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().get(0).prompt()).isEqualTo("first");
    }

    @Test
    void feedNonDefaultLimitIsNotCached() {
        images.save(image("first", Instant.parse("2025-01-01T00:00:00Z")));

        query.getFeed(null, 10);

        images.save(image("second", Instant.parse("2025-01-02T00:00:00Z")));

        CursorPage<ImageResponse> second = query.getFeed(null, 10);
        assertThat(second.items()).hasSize(2);
    }

    @Test
    void feedSecondPageIsNotCached() {
        images.save(image("a", Instant.parse("2025-01-01T00:00:00Z")));
        images.save(image("b", Instant.parse("2025-01-02T00:00:00Z")));

        CursorPage<ImageResponse> first = query.getFeed(null, 20);
        String cursor = first.nextCursor();
        assertThat(cursor).isNull();

        query.getFeed("some-cursor", 20);
    }

    @Test
    void tagsCachedOnSecondCall() {
        CatalogImage img = images.save(image("a", Instant.now()));
        imageTags.save(CatalogImageTag.of(img.getId(), "cat"));

        List<TagCountResponse> first = query.listTags();
        assertThat(first).hasSize(1);

        imageTags.save(CatalogImageTag.of(img.getId(), "cute"));

        List<TagCountResponse> second = query.listTags();
        assertThat(second).hasSize(1);
        assertThat(second.get(0).tag()).isEqualTo("cat");
    }

    @Test
    void generationCompletedEvictsFeedCache() {
        images.save(image("first", Instant.parse("2025-01-01T00:00:00Z")));
        query.getFeed(null, 20);

        UUID generationId = UUID.randomUUID();
        writer.applyGenerationCompleted(new GenerationCompletedEvent(
                generationId, null, "second",
                "orig", "k1", "thumb", "k2",
                "pollinations", Instant.parse("2025-01-02T00:00:00Z")));

        CursorPage<ImageResponse> after = query.getFeed(null, 20);
        assertThat(after.items()).hasSize(2);
    }

    @Test
    void tagsExtractedEvictsTagsAndFeedCache() {
        CatalogImage img = images.save(image("a", Instant.now()));
        imageTags.save(CatalogImageTag.of(img.getId(), "cat"));
        query.listTags();
        query.getFeed(null, 20);

        writer.applyTagsExtracted(new TagsExtractedEvent(
                img.getId(), null,
                List.of(new TagsExtractedEvent.Tag("cute", new BigDecimal("1.0"))),
                Instant.now()));

        List<TagCountResponse> tagsAfter = query.listTags();
        assertThat(tagsAfter).hasSize(1);
        assertThat(tagsAfter.get(0).tag()).isEqualTo("cute");

        CursorPage<ImageResponse> feedAfter = query.getFeed(null, 20);
        assertThat(feedAfter.items().get(0).tags()).containsExactly("cute");
    }
}