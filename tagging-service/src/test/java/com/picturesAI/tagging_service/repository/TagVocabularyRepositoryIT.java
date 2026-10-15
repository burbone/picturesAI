package com.picturesAI.tagging_service.repository;

import com.picturesAI.tagging_service.domain.TagVocabulary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class TagVocabularyRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tags_db")
            .withUsername("picturesai")
            .withPassword("picturesAI");

    @Autowired
    TagVocabularyRepository repository;

    @Test
    void seedContainsExpectedCategories() {
        List<TagVocabulary> active = repository.findByActiveTrue();

        assertThat(active).hasSize(49);
        assertThat(active).extracting(TagVocabulary::getCategory)
                .contains("subject", "style", "color", "mood", "composition");
    }

    @Test
    void findByActiveTrueExcludesInactive() {
        TagVocabulary inactive = new TagVocabulary();
        inactive.setName("test-inactive");
        inactive.setCategory("subject");
        inactive.setAliases(new String[]{"неактивный"});
        inactive.setActive(false);
        repository.saveAndFlush(inactive);

        List<TagVocabulary> active = repository.findByActiveTrue();

        assertThat(active).hasSize(49);
        assertThat(active).extracting(TagVocabulary::getName).doesNotContain("test-inactive");
    }

    @Test
    void findByActiveTrueIsCaseSensitiveAndExactMatch() {
        List<TagVocabulary> active = repository.findByActiveTrue();

        assertThat(active).extracting(TagVocabulary::getName).contains("cat");
        assertThat(active).extracting(TagVocabulary::getName).doesNotContain("Cat");
    }

    @Test
    void aliasesRoundTripCyrillicTextArray() {
        TagVocabulary cat = repository.findByName("cat").orElseThrow();

        assertThat(cat.getAliases()).containsExactlyInAnyOrder(
                "кот", "кошка", "kitten", "котёнок");
    }

    @Test
    void emptyAliasesDefaultToEmptyArrayOnPersist() {
        TagVocabulary v = new TagVocabulary();
        v.setName("test-empty-aliases");
        v.setCategory("subject");
        v.setActive(true);
        v.setAliases(null);
        repository.saveAndFlush(v);

        TagVocabulary loaded = repository.findByName("test-empty-aliases").orElseThrow();
        assertThat(loaded.getAliases()).isNotNull();
        assertThat(loaded.getAliases()).isEmpty();
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void findByNameReturnsEmptyForUnknown() {
        Optional<TagVocabulary> result = repository.findByName("does-not-exist-" + UUID.randomUUID());
        assertThat(result).isEmpty();
    }

    @Test
    void uniqueNameConstraintOnInsert() {
        TagVocabulary dup = new TagVocabulary();
        dup.setName("cat");
        dup.setCategory("subject");
        dup.setAliases(new String[]{"x"});
        dup.setActive(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.saveAndFlush(dup))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}