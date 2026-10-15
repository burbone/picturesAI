package com.picturesAI.catalog_service.service;

import com.picturesAI.catalog_service.AbstractPostgresTest;
import com.picturesAI.catalog_service.api.CursorCodec;
import com.picturesAI.catalog_service.api.dto.ImageResponse;
import com.picturesAI.catalog_service.domain.CatalogImage;
import com.picturesAI.catalog_service.domain.CatalogImageTag;
import com.picturesAI.catalog_service.repository.CatalogImageRepository;
import com.picturesAI.catalog_service.repository.CatalogImageTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@Import({RecommendationService.class, CatalogQueryService.class, CursorCodec.class})
class RecommendationServiceIT extends AbstractPostgresTest {

    @MockitoBean
    private PresignedUrlService presignedUrlService;

    @Autowired
    private RecommendationService recommendationService;

    @Autowired
    private CatalogImageRepository images;

    @Autowired
    private CatalogImageTagRepository imageTags;

    @Autowired
    private TestEntityManager em;

    @BeforeEach
    void stubPresigner() {
        lenient().when(presignedUrlService.presign(any(), any()))
                .thenAnswer(inv -> "https://minio/" + inv.getArgument(0) + "/" + inv.getArgument(1));
    }

    private CatalogImage image(UUID userId, String prompt, boolean isPublic, Instant completedAt) {
        CatalogImage img = new CatalogImage();
        img.setId(UUID.randomUUID());
        img.setUserId(userId);
        img.setPrompt(prompt);
        img.setOriginalsBucket("orig");
        img.setOriginalsKey("k/" + img.getId());
        img.setThumbnailsBucket("thumb");
        img.setThumbnailsKey("k/" + img.getId());
        img.setProvider("mock");
        img.setPublic(isPublic);
        img.setCompletedAt(completedAt);
        return img;
    }

    private void tag(CatalogImage img, String name, String weight) {
        imageTags.save(CatalogImageTag.of(img.getId(), name, new BigDecimal(weight)));
    }

    @Test
    void recommendsByTopTagsExcludingOwnAndPrivate() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID user3 = UUID.randomUUID();
        Instant base = Instant.parse("2025-01-01T00:00:00Z");

        CatalogImage u1a = images.save(image(user1, "a", true, base.plusSeconds(1)));
        CatalogImage u1b = images.save(image(user1, "b", true, base.plusSeconds(2)));
        CatalogImage u1c = images.save(image(user1, "c", true, base.plusSeconds(3)));
        tag(u1a, "cat", "1.0");
        tag(u1b, "dog", "1.0");
        tag(u1c, "sunset", "1.0");

        CatalogImage u2a = images.save(image(user2, "pub-cat-dog", true, base.plusSeconds(10)));
        CatalogImage u2b = images.save(image(user2, "private-cat", false, base.plusSeconds(11)));
        CatalogImage u2c = images.save(image(user2, "no-tags-public", true, base.plusSeconds(12)));
        tag(u2a, "cat", "1.0");
        tag(u2a, "dog", "1.0");
        tag(u2b, "cat", "1.0");

        CatalogImage u3a = images.save(image(user3, "ocean", true, base.plusSeconds(20)));
        tag(u3a, "ocean", "1.0");

        em.flush();
        em.clear();

        List<ImageResponse> result = recommendationService.recommend(user1, 20);

        List<UUID> ids = result.stream().map(ImageResponse::id).toList();
        assertThat(ids).contains(u2a.getId());
        assertThat(ids).doesNotContain(u2b.getId());
        assertThat(ids).doesNotContain(u2c.getId());
        assertThat(ids).doesNotContain(u3a.getId());
        assertThat(ids).doesNotContain(u1a.getId(), u1b.getId(), u1c.getId());
    }

    @Test
    void coldStartReturnsOnlyImagesWithTags() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        Instant base = Instant.parse("2025-01-01T00:00:00Z");

        CatalogImage olderTagged = images.save(image(user2, "old-tagged", true, base));
        CatalogImage newerUntagged = images.save(image(user2, "new-untagged", true, base.plusSeconds(10)));
        CatalogImage newestTagged = images.save(image(user2, "new-tagged", true, base.plusSeconds(20)));
        tag(olderTagged, "cat", "1.0");
        tag(newestTagged, "cat", "1.0");
        em.flush();
        em.clear();

        List<ImageResponse> result = recommendationService.recommend(user1, 20);

        List<UUID> ids = result.stream().map(ImageResponse::id).toList();
        assertThat(ids).containsExactly(newestTagged.getId(), olderTagged.getId());
        assertThat(ids).doesNotContain(newerUntagged.getId());
    }

    @Test
    void nullUserFallsBackToTaggedOnly() {
        UUID user2 = UUID.randomUUID();
        CatalogImage tagged = images.save(image(user2, "tagged", true, Instant.parse("2025-01-01T00:00:00Z")));
        CatalogImage untagged = images.save(image(user2, "untagged", true, Instant.parse("2025-01-02T00:00:00Z")));
        tag(tagged, "cat", "1.0");
        em.flush();
        em.clear();

        List<ImageResponse> result = recommendationService.recommend(null, 20);

        List<UUID> ids = result.stream().map(ImageResponse::id).toList();
        assertThat(ids).containsExactly(tagged.getId());
        assertThat(ids).doesNotContain(untagged.getId());
    }

    @Test
    void fallbackSkipsPrivateImagesEvenWithTags() {
        UUID user2 = UUID.randomUUID();
        CatalogImage publicTagged = images.save(image(user2, "public", true, Instant.parse("2025-01-01T00:00:00Z")));
        CatalogImage privateTagged = images.save(image(user2, "private", false, Instant.parse("2025-01-02T00:00:00Z")));
        tag(publicTagged, "cat", "1.0");
        tag(privateTagged, "cat", "1.0");
        em.flush();
        em.clear();

        List<ImageResponse> result = recommendationService.recommend(null, 20);

        List<UUID> ids = result.stream().map(ImageResponse::id).toList();
        assertThat(ids).containsExactly(publicTagged.getId());
        assertThat(ids).doesNotContain(privateTagged.getId());
    }
}