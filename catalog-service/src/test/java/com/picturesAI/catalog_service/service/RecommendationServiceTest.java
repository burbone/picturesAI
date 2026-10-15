package com.picturesAI.catalog_service.service;

import com.picturesAI.catalog_service.api.dto.ImageResponse;
import com.picturesAI.catalog_service.domain.CatalogImage;
import com.picturesAI.catalog_service.repository.CatalogImageRepository;
import com.picturesAI.catalog_service.repository.CatalogImageTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private CatalogImageRepository images;

    @Mock
    private CatalogImageTagRepository imageTags;

    @Mock
    private CatalogQueryService queryService;

    private RecommendationService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationService(images, imageTags, queryService);
    }

    private CatalogImage image(UUID id, UUID userId) {
        CatalogImage img = new CatalogImage();
        img.setId(id);
        img.setUserId(userId);
        img.setPrompt("p");
        img.setOriginalsBucket("orig");
        img.setOriginalsKey("k");
        img.setThumbnailsBucket("thumb");
        img.setThumbnailsKey("k");
        img.setCompletedAt(Instant.now());
        return img;
    }

    private Object[] row(String tag, long cnt) {
        return new Object[]{tag, cnt};
    }

    @Test
    void fallsBackWhenUserIdNull() {
        UUID id = UUID.randomUUID();
        when(images.findRecentPublicWithTags(20)).thenReturn(List.of(id));
        when(images.findByIds(List.of(id))).thenReturn(List.of(image(id, null)));
        when(queryService.toImageResponses(anyList())).thenReturn(List.of());

        List<ImageResponse> result = service.recommend(null, 20);

        assertThat(result).isEmpty();
        verify(imageTags, never()).findTopTagsByUserId(any(), anyInt());
        verify(images).findRecentPublicWithTags(20);
        verify(images, never()).findFeed(any(), any(), anyInt());
    }

    @Test
    void fallbackReturnsOnlyImagesWithTagsQuery() {
        when(images.findRecentPublicWithTags(20)).thenReturn(List.of());

        List<ImageResponse> result = service.recommend(null, 20);

        assertThat(result).isEmpty();
        verify(images).findRecentPublicWithTags(20);
        verify(images, never()).findFeed(any(), any(), anyInt());
        verify(images, never()).findByIds(anyList());
    }

    @Test
    void fallsBackWhenFewerThanThreeTags() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(imageTags.findTopTagsByUserId(userId, 10))
                .thenReturn(List.of(row("cat", 2L), row("dog", 2L)));
        when(images.findRecentPublicWithTags(20)).thenReturn(List.of(id));
        when(images.findByIds(List.of(id))).thenReturn(List.of(image(id, null)));
        when(queryService.toImageResponses(anyList())).thenReturn(List.of());

        service.recommend(userId, 20);

        verify(images, never()).findRecommendedIds(anyList(), any(), anyInt());
        verify(images).findRecentPublicWithTags(20);
    }

    @Test
    void usesRecommendedIdsWhenEnoughTags() {
        UUID userId = UUID.randomUUID();
        UUID imgId = UUID.randomUUID();
        when(imageTags.findTopTagsByUserId(userId, 10))
                .thenReturn(List.of(row("cat", 3L), row("dog", 3L), row("sunset", 1L)));
        when(images.findRecommendedIds(anyList(), eq(userId), eq(20)))
                .thenReturn(List.of(imgId));
        when(images.findByIds(List.of(imgId)))
                .thenReturn(List.of(image(imgId, UUID.randomUUID())));
        when(queryService.toImageResponses(anyList())).thenReturn(List.of());

        service.recommend(userId, 20);

        verify(images).findRecommendedIds(anyList(), eq(userId), eq(20));
        verify(images, never()).findRecentPublicWithTags(anyInt());
    }

    @Test
    void fallsBackWhenRecommendedIdsEmpty() {
        UUID userId = UUID.randomUUID();
        UUID fallbackId = UUID.randomUUID();
        when(imageTags.findTopTagsByUserId(userId, 10))
                .thenReturn(List.of(row("cat", 3L), row("dog", 3L), row("sunset", 1L)));
        when(images.findRecommendedIds(anyList(), eq(userId), eq(20)))
                .thenReturn(List.of());
        when(images.findRecentPublicWithTags(20)).thenReturn(List.of(fallbackId));
        when(images.findByIds(List.of(fallbackId)))
                .thenReturn(List.of(image(fallbackId, null)));
        when(queryService.toImageResponses(anyList())).thenReturn(List.of());

        service.recommend(userId, 20);

        verify(images).findRecentPublicWithTags(20);
    }

    @Test
    void preservesRecommendedOrder() {
        UUID userId = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(imageTags.findTopTagsByUserId(userId, 10))
                .thenReturn(List.of(row("cat", 3L), row("dog", 3L), row("sunset", 1L)));
        when(images.findRecommendedIds(anyList(), eq(userId), eq(20)))
                .thenReturn(List.of(a, b));
        when(images.findByIds(List.of(a, b)))
                .thenReturn(List.of(image(b, UUID.randomUUID()), image(a, UUID.randomUUID())));
        when(queryService.toImageResponses(anyList())).thenReturn(List.of());

        service.recommend(userId, 20);

        verify(queryService).toImageResponses(anyList());
    }

    @Test
    void emptyFallbackReturnsEmptyListWithoutLoading() {
        when(images.findRecentPublicWithTags(20)).thenReturn(List.of());

        List<ImageResponse> result = service.recommend(null, 20);

        assertThat(result).isEmpty();
        verify(images, never()).findByIds(anyList());
        verify(queryService, never()).toImageResponses(anyList());
    }
}