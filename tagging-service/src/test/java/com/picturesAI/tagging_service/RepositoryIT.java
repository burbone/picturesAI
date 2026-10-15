package com.picturesAI.tagging_service;

import com.picturesAI.tagging_service.domain.GenerationTag;
import com.picturesAI.tagging_service.domain.GenerationTagId;
import com.picturesAI.tagging_service.domain.OutboxEvent;
import com.picturesAI.tagging_service.domain.TagStat;
import com.picturesAI.tagging_service.domain.TagVocabulary;
import com.picturesAI.tagging_service.repository.GenerationTagRepository;
import com.picturesAI.tagging_service.repository.OutboxRepository;
import com.picturesAI.tagging_service.repository.TagStatRepository;
import com.picturesAI.tagging_service.repository.TagVocabularyRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class RepositoryIT {

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
    TagVocabularyRepository vocabulary;

    @Autowired
    GenerationTagRepository generationTags;

    @Autowired
    TagStatRepository stats;

    @Autowired
    OutboxRepository outbox;

    @Test
    void vocabularySeedIsReadable() {
        List<TagVocabulary> active = vocabulary.findByActiveTrue();
        assertThat(active).hasSize(49);
        assertThat(active).extracting(TagVocabulary::getName).contains("cat", "dog", "cyberpunk", "sunset");
    }

    @Test
    void vocabularyAliasesRoundTripCyrillic() {
        TagVocabulary cat = vocabulary.findByName("cat").orElseThrow();
        assertThat(cat.getAliases()).contains("кот", "кошка", "kitten", "котёнок");
        assertThat(cat.getCategory()).isEqualTo("subject");
        assertThat(cat.isActive()).isTrue();
    }

    @Test
    void saveAndReadNewVocabularyRow() {
        TagVocabulary v = new TagVocabulary();
        v.setName("test-tag");
        v.setCategory("subject");
        v.setAliases(new String[]{"тест", "test-alias"});
        TagVocabulary saved = vocabulary.saveAndFlush(v);

        TagVocabulary loaded = vocabulary.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("test-tag");
        assertThat(loaded.getAliases()).containsExactly("тест", "test-alias");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void generationTagsExistCheckAndFind() {
        UUID generationId = UUID.randomUUID();
        TagVocabulary cat = vocabulary.findByName("cat").orElseThrow();

        GenerationTag gt = new GenerationTag();
        gt.setId(new GenerationTagId(generationId, cat.getId()));
        gt.setWeight(new BigDecimal("0.90"));
        generationTags.saveAndFlush(gt);

        assertThat(generationTags.existsByGenerationId(generationId)).isTrue();
        assertThat(generationTags.existsByGenerationId(UUID.randomUUID())).isFalse();

        List<GenerationTag> rows = generationTags.findByGenerationId(generationId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getWeight()).isEqualByComparingTo("0.90");
        assertThat(rows.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    void tagStatsIncrementByTagId() {
        TagVocabulary cat = vocabulary.findByName("cat").orElseThrow();
        TagVocabulary dog = vocabulary.findByName("dog").orElseThrow();

        TagStat catBefore = stats.findById(cat.getId()).orElseThrow();
        TagStat dogBefore = stats.findById(dog.getId()).orElseThrow();
        long catBeforeCount = catBefore.getUsageCount();
        long dogBeforeCount = dogBefore.getUsageCount();

        int updated = stats.incrementUsage(List.of(cat.getId(), dog.getId()));

        assertThat(updated).isEqualTo(2);
        assertThat(stats.findById(cat.getId()).orElseThrow().getUsageCount())
                .isEqualTo(catBeforeCount + 1);
        assertThat(stats.findById(dog.getId()).orElseThrow().getUsageCount())
                .isEqualTo(dogBeforeCount + 1);
    }

    @Test
    void outboxJsonbPayloadRoundTrip() {
        OutboxEvent e = new OutboxEvent();
        e.setAggregateId(UUID.randomUUID());
        e.setEventType("tags.extracted");
        e.setPayload("{\"generationId\":\"abc\",\"tags\":[{\"name\":\"cat\",\"weight\":0.9}]}");
        outbox.saveAndFlush(e);

        List<OutboxEvent> unpublished =
                outbox.findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, 10));

        assertThat(unpublished).hasSize(1);
        assertThat(unpublished.get(0).getEventType()).isEqualTo("tags.extracted");
        assertThat(unpublished.get(0).getPayload()).contains("\"name\":\"cat\"");
        assertThat(unpublished.get(0).getPublishedAt()).isNull();
        assertThat(unpublished.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    void outboxExcludesPublished() {
        OutboxEvent e = new OutboxEvent();
        e.setAggregateId(UUID.randomUUID());
        e.setEventType("tags.extracted");
        e.setPayload("{}");
        e.setPublishedAt(Instant.now());
        outbox.saveAndFlush(e);

        assertThat(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, 10)))
                .isEmpty();
    }
}