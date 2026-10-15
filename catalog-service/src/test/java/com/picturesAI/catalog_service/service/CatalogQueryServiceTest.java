package com.picturesAI.catalog_service.service;

import com.picturesAI.catalog_service.api.CursorCodec;
import com.picturesAI.catalog_service.api.CursorPage;
import com.picturesAI.catalog_service.api.ImageNotFoundException;
import com.picturesAI.catalog_service.api.dto.ImageResponse;
import com.picturesAI.catalog_service.api.dto.TagCountResponse;
import com.picturesAI.catalog_service.domain.CatalogImage;
import com.picturesAI.catalog_service.domain.CatalogImageTag;
import com.picturesAI.catalog_service.domain.CatalogUser;
import com.picturesAI.catalog_service.repository.CatalogImageRepository;
import com.picturesAI.catalog_service.repository.CatalogImageTagRepository;
import com.picturesAI.catalog_service.repository.CatalogUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogQueryServiceTest {

    @Mock
    private CatalogImageRepository images;

    @Mock
    private CatalogImageTagRepository imageTags;

    @Mock
    private CatalogUserRepository users;

    @Mock
    private PresignedUrlService presigner;

    private final CursorCodec codec = new CursorCodec();

    private CatalogQueryService service;

    @BeforeEach
    void setUp() {
        service = new CatalogQueryService(images, imageTags, users, presigner, codec);
        lenient().when(presigner.presign(any(), any())).thenAnswer(inv ->
                "https://minio/" + inv.getArgument(0) + "/" + inv.getArgument(1));
        lenient().when(users.findByIds(anyCollection())).thenReturn(List.of());
        lenient().when(imageTags.findByImageIds(anyCollection())).thenReturn(List.of());
    }

    private CatalogImage image(String prompt, UUID userId, Instant completedAt) {
        CatalogImage img = new CatalogImage();
        img.setId(UUID.randomUUID());
        img.setPrompt(prompt);
        img.setUserId(userId);
        img.setOriginalsBucket("orig");
        img.setOriginalsKey("k/" + img.getId());
        img.setThumbnailsBucket("thumb");
        img.setThumbnailsKey("k/" + img.getId());
        img.setProvider("pollinations");
        img.setCompletedAt(completedAt);
        return img;
    }

    private CatalogUser user(UUID id, String username) {
        CatalogUser u = new CatalogUser();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(username);
        return u;
    }

    @Test
    void getFeedMapsRowsToResponses() {
        UUID userId = UUID.randomUUID();
        Instant base = Instant.parse("2025-01-01T00:00:00Z");
        CatalogImage img = image("cat", userId, base);
        when(images.findFeed(isNull(), isNull(), eq(21))).thenReturn(List.of(img));
        when(users.findByIds(anyCollection())).thenReturn(List.of(user(userId, "alice")));
        when(imageTags.findByImageIds(anyCollection()))
                .thenReturn(List.of(CatalogImageTag.of(img.getId(), "cat")));

        CursorPage<ImageResponse> page = service.getFeed(null, 20);

        assertThat(page.items()).hasSize(1);
        ImageResponse item = page.items().get(0);
        assertThat(item.id()).isEqualTo(img.getId());
        assertThat(item.prompt()).isEqualTo("cat");
        assertThat(item.imageUrl()).isEqualTo("https://minio/orig/k/" + img.getId());
        assertThat(item.thumbnailUrl()).isEqualTo("https://minio/thumb/k/" + img.getId());
        assertThat(item.tags()).containsExactly("cat");
        assertThat(item.author()).isNotNull();
        assertThat(item.author().username()).isEqualTo("alice");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void getFeedEmitsCursorWhenMoreRows() {
        Instant base = Instant.parse("2025-01-01T00:00:00Z");
        CatalogImage a = image("a", null, base.plus(2, ChronoUnit.HOURS));
        CatalogImage b = image("b", null, base.plus(1, ChronoUnit.HOURS));
        CatalogImage c = image("c", null, base);
        when(images.findFeed(isNull(), isNull(), eq(3))).thenReturn(List.of(a, b, c));

        CursorPage<ImageResponse> page = service.getFeed(null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        CursorCodec.Cursor decoded = codec.decode(page.nextCursor());
        assertThat(decoded.id()).isEqualTo(b.getId());
    }

    @Test
    void getFeedUsesCursorForNextPage() {
        Instant base = Instant.parse("2025-01-01T00:00:00Z");
        UUID id = UUID.randomUUID();
        String cursor = codec.encode(base, id);
        when(images.findFeed(eq(base), eq(id), eq(21))).thenReturn(List.of());

        CursorPage<ImageResponse> page = service.getFeed(cursor, 20);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void getFeedEmptyRowsReturnsEmptyPage() {
        when(images.findFeed(isNull(), isNull(), anyInt())).thenReturn(List.of());

        CursorPage<ImageResponse> page = service.getFeed(null, 20);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void getImageReturnsAuthorAndTags() {
        UUID userId = UUID.randomUUID();
        CatalogImage img = image("cat", userId, Instant.now());
        when(images.findById(img.getId())).thenReturn(Optional.of(img));
        when(users.findById(userId)).thenReturn(Optional.of(user(userId, "alice")));
        when(imageTags.findByImageId(img.getId()))
                .thenReturn(List.of(
                        CatalogImageTag.of(img.getId(), "cat"),
                        CatalogImageTag.of(img.getId(), "cute")));

        ImageResponse response = service.getImage(img.getId());

        assertThat(response.id()).isEqualTo(img.getId());
        assertThat(response.tags()).containsExactly("cat", "cute");
        assertThat(response.author().username()).isEqualTo("alice");
    }

    @Test
    void getImageWithoutAuthorReturnsNullAuthor() {
        CatalogImage img = image("anon", null, Instant.now());
        when(images.findById(img.getId())).thenReturn(Optional.of(img));
        when(imageTags.findByImageId(img.getId())).thenReturn(List.of());

        ImageResponse response = service.getImage(img.getId());

        assertThat(response.author()).isNull();
        assertThat(response.userId()).isNull();
    }

    @Test
    void getImageMissingThrows() {
        UUID id = UUID.randomUUID();
        when(images.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getImage(id))
                .isInstanceOf(ImageNotFoundException.class);
    }

    @Test
    void getImageAnonymizedAuthorKeepsFlag() {
        UUID userId = UUID.randomUUID();
        CatalogImage img = image("cat", userId, Instant.now());
        CatalogUser anonymized = new CatalogUser();
        anonymized.setId(userId);
        anonymized.setAnonymized(true);
        when(images.findById(img.getId())).thenReturn(Optional.of(img));
        when(users.findById(userId)).thenReturn(Optional.of(anonymized));
        when(imageTags.findByImageId(img.getId())).thenReturn(List.of());

        ImageResponse response = service.getImage(img.getId());

        assertThat(response.author()).isNotNull();
        assertThat(response.author().anonymized()).isTrue();
        assertThat(response.author().username()).isNull();
    }

    @Test
    void listTagsMapsProjections() {
        when(imageTags.findTagCounts()).thenReturn(List.of(
                projection("cat", 5L),
                projection("cute", 2L)));

        List<TagCountResponse> tags = service.listTags();

        assertThat(tags).hasSize(2);
        assertThat(tags.get(0).tag()).isEqualTo("cat");
        assertThat(tags.get(0).count()).isEqualTo(5L);
    }

    @Test
    void getImagesByTagPassesCursorAndLimit() {
        Instant base = Instant.parse("2025-01-01T00:00:00Z");
        UUID id = UUID.randomUUID();
        String cursor = codec.encode(base, id);
        when(images.findByTag(eq("cat"), eq(base), eq(id), eq(21))).thenReturn(List.of());

        CursorPage<ImageResponse> page = service.getImagesByTag("cat", cursor, 20);

        assertThat(page.items()).isEmpty();
    }

    @Test
    void searchPassesQuery() {
        when(images.search(eq("happy cat"), isNull(), isNull(), eq(21))).thenReturn(List.of());

        CursorPage<ImageResponse> page = service.search("happy cat", null, 20);

        assertThat(page.items()).isEmpty();
    }

    @Test
    void getUserGenerationsFiltersByUser() {
        UUID userId = UUID.randomUUID();
        CatalogImage img = image("mine", userId, Instant.now());
        when(images.findByUser(eq(userId), isNull(), isNull(), eq(21)))
                .thenReturn(List.of(img));
        when(users.findByIds(anyCollection())).thenReturn(List.of(user(userId, "alice")));

        CursorPage<ImageResponse> page = service.getUserGenerations(userId, null, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).userId()).isEqualTo(userId);
    }

    private CatalogImageTagRepository.TagCountProjection projection(String tag, long count) {
        return new CatalogImageTagRepository.TagCountProjection() {
            @Override
            public String getTag() {
                return tag;
            }

            @Override
            public Long getTotal() {
                return count;
            }
        };
    }
}