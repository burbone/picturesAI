package com.picturesAI.catalog_service.repository;

import com.picturesAI.catalog_service.AbstractPostgresTest;
import com.picturesAI.catalog_service.domain.CatalogImage;
import com.picturesAI.catalog_service.domain.CatalogImageTag;
import com.picturesAI.catalog_service.domain.CatalogUser;
import com.picturesAI.catalog_service.domain.ProcessedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogRepositoryIT extends AbstractPostgresTest {

    @Autowired
    private CatalogImageRepository images;

    @Autowired
    private CatalogImageTagRepository tags;

    @Autowired
    private CatalogUserRepository users;

    @Autowired
    private ProcessedEventRepository processed;

    @Autowired
    private TestEntityManager em;

    private CatalogImage image(String prompt, UUID userId, Instant completedAt) {
        CatalogImage img = new CatalogImage();
        img.setId(UUID.randomUUID());
        img.setPrompt(prompt);
        img.setUserId(userId);
        img.setOriginalsBucket("ai-gallery-originals");
        img.setOriginalsKey("k/" + img.getId() + ".png");
        img.setThumbnailsBucket("ai-gallery-thumbnails");
        img.setThumbnailsKey("k/" + img.getId() + ".png");
        img.setProvider("pollinations");
        img.setCompletedAt(completedAt);
        return img;
    }

    @Test
    void saveAndFindImage() {
        CatalogImage saved = images.save(image("a cat", null, Instant.now()));
        em.flush();
        em.clear();

        assertThat(images.findById(saved.getId())).isPresent();
    }

    @Test
    void saveIsUpsertById() {
        UUID id = UUID.randomUUID();
        CatalogImage first = image("original", null, Instant.parse("2025-01-01T00:00:00Z"));
        first.setId(id);
        images.save(first);
        em.flush();
        em.clear();

        CatalogImage second = image("updated", null, Instant.parse("2025-01-01T00:00:00Z"));
        second.setId(id);
        images.save(second);
        em.flush();
        em.clear();

        CatalogImage found = images.findById(id).orElseThrow();
        assertThat(found.getPrompt()).isEqualTo("updated");
    }

    @Test
    void catalogImagePrePersistSetsCreatedAt() {
        CatalogImage img = image("x", null, Instant.now());
        assertThat(img.getCreatedAt()).isNull();

        images.save(img);
        em.flush();

        assertThat(img.getCreatedAt()).isNotNull();
    }

    @Test
    void feedReturnsNewestFirst() {
        Instant base = Instant.parse("2025-01-01T00:00:00Z");
        images.save(image("first", null, base));
        images.save(image("second", null, base.plus(1, ChronoUnit.HOURS)));
        images.save(image("third", null, base.plus(2, ChronoUnit.HOURS)));
        em.flush();
        em.clear();

        List<CatalogImage> page = images.findFeed(null, null, 10);

        assertThat(page).hasSize(3);
        assertThat(page).extracting(CatalogImage::getPrompt)
                .containsExactly("third", "second", "first");
    }

    @Test
    void feedCursorPaginates() {
        Instant base = Instant.parse("2025-01-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            images.save(image("p" + i, null, base.plus(i, ChronoUnit.HOURS)));
        }
        em.flush();
        em.clear();

        List<CatalogImage> first = images.findFeed(null, null, 2);
        assertThat(first).hasSize(2);
        assertThat(first).extracting(CatalogImage::getPrompt)
                .containsExactly("p4", "p3");

        CatalogImage last = first.get(first.size() - 1);
        List<CatalogImage> second = images.findFeed(last.getCompletedAt(), last.getId(), 2);
        assertThat(second).hasSize(2);
        assertThat(second).extracting(CatalogImage::getPrompt)
                .containsExactly("p2", "p1");
    }

    @Test
    void findByUserFilters() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        images.save(image("mine", a, Instant.now()));
        images.save(image("theirs", b, Instant.now()));
        em.flush();
        em.clear();

        List<CatalogImage> page = images.findByUser(a, null, null, 10);

        assertThat(page).hasSize(1);
        assertThat(page.get(0).getPrompt()).isEqualTo("mine");
    }

    @Test
    void searchMatchesPromptTokens() {
        images.save(image("a happy cat in the garden", null, Instant.now()));
        images.save(image("a sad dog", null, Instant.now()));
        em.flush();
        em.clear();

        List<CatalogImage> result = images.search("cat", null, null, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPrompt()).contains("cat");
    }

    @Test
    void findByTagJoinsCorrectly() {
        CatalogImage cat = images.save(image("cat", null, Instant.now()));
        CatalogImage dog = images.save(image("dog", null, Instant.now()));
        tags.save(CatalogImageTag.of(cat.getId(), "animal"));
        tags.save(CatalogImageTag.of(dog.getId(), "animal"));
        tags.save(CatalogImageTag.of(cat.getId(), "cute"));
        em.flush();
        em.clear();

        List<CatalogImage> byAnimal = images.findByTag("animal", null, null, 10);
        List<CatalogImage> byCute = images.findByTag("cute", null, null, 10);

        assertThat(byAnimal).hasSize(2);
        assertThat(byCute).hasSize(1);
        assertThat(byCute.get(0).getPrompt()).isEqualTo("cat");
    }

    @Test
    void tagCountsOrderedByFrequency() {
        CatalogImage a = images.save(image("a", null, Instant.now()));
        CatalogImage b = images.save(image("b", null, Instant.now()));
        tags.save(CatalogImageTag.of(a.getId(), "cat"));
        tags.save(CatalogImageTag.of(b.getId(), "cat"));
        tags.save(CatalogImageTag.of(a.getId(), "cute"));
        em.flush();
        em.clear();

        List<CatalogImageTagRepository.TagCountProjection> counts = tags.findTagCounts();

        assertThat(counts).hasSize(2);
        assertThat(counts.get(0).getTag()).isEqualTo("cat");
        assertThat(counts.get(0).getTotal()).isEqualTo(2L);
        assertThat(counts.get(1).getTag()).isEqualTo("cute");
        assertThat(counts.get(1).getTotal()).isEqualTo(1L);
    }

    @Test
    void findAllTagsOrderedAlphabetically() {
        CatalogImage a = images.save(image("a", null, Instant.now()));
        tags.save(CatalogImageTag.of(a.getId(), "zebra"));
        tags.save(CatalogImageTag.of(a.getId(), "apple"));
        tags.save(CatalogImageTag.of(a.getId(), "mango"));
        em.flush();
        em.clear();

        List<String> all = tags.findAllTags();

        assertThat(all).containsExactly("apple", "mango", "zebra");
    }

    @Test
    void deleteTagsByImageId() {
        CatalogImage a = images.save(image("a", null, Instant.now()));
        tags.save(CatalogImageTag.of(a.getId(), "x"));
        tags.save(CatalogImageTag.of(a.getId(), "y"));
        em.flush();

        tags.deleteByImageId(a.getId());
        em.flush();
        em.clear();

        assertThat(tags.findTagCounts()).isEmpty();
    }

    @Test
    void deleteTagsByImageIdOnlyTouchesTarget() {
        CatalogImage a = images.save(image("a", null, Instant.now()));
        CatalogImage b = images.save(image("b", null, Instant.now()));
        tags.save(CatalogImageTag.of(a.getId(), "x"));
        tags.save(CatalogImageTag.of(b.getId(), "x"));
        em.flush();

        tags.deleteByImageId(a.getId());
        em.flush();
        em.clear();

        List<CatalogImageTagRepository.TagCountProjection> counts = tags.findTagCounts();
        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).getTag()).isEqualTo("x");
        assertThat(counts.get(0).getTotal()).isEqualTo(1L);
    }

    @Test
    void saveAndFindUser() {
        CatalogUser u = new CatalogUser();
        u.setId(UUID.randomUUID());
        u.setKeycloakId(UUID.randomUUID());
        u.setUsername("alice");
        u.setDisplayName("Alice");
        users.save(u);
        em.flush();
        em.clear();

        assertThat(users.findById(u.getId())).isPresent();
    }

    @Test
    void catalogUserPrePersistSetsTimestamps() {
        CatalogUser u = new CatalogUser();
        u.setId(UUID.randomUUID());
        u.setUsername("bob");
        assertThat(u.getCreatedAt()).isNull();
        assertThat(u.getUpdatedAt()).isNull();

        users.save(u);
        em.flush();

        assertThat(u.getCreatedAt()).isNotNull();
        assertThat(u.getUpdatedAt()).isNotNull();
    }

    @Test
    void catalogUserPreUpdateChangesUpdatedAt() throws InterruptedException {
        CatalogUser u = new CatalogUser();
        u.setId(UUID.randomUUID());
        u.setUsername("carol");
        users.save(u);
        em.flush();
        Instant createdAt = u.getCreatedAt();
        Instant firstUpdatedAt = u.getUpdatedAt();

        Thread.sleep(10);
        u.setDisplayName("Carol");
        users.save(u);
        em.flush();
        em.clear();

        CatalogUser reloaded = users.findById(u.getId()).orElseThrow();
        assertThat(reloaded.getUpdatedAt()).isAfter(firstUpdatedAt);
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void processedEventIdempotency() {
        ProcessedEvent e = new ProcessedEvent();
        e.setEventId("generation.completed:" + UUID.randomUUID());
        e.setEventType("generation.completed");
        processed.save(e);
        em.flush();
        em.clear();

        assertThat(processed.existsById(e.getEventId())).isTrue();
    }

    @Test
    void processedEventPrePersistSetsProcessedAt() {
        ProcessedEvent e = new ProcessedEvent();
        e.setEventId("user.created:" + UUID.randomUUID());
        e.setEventType("user.created");
        assertThat(e.getProcessedAt()).isNull();

        processed.save(e);
        em.flush();

        assertThat(e.getProcessedAt()).isNotNull();
    }
}