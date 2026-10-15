package com.picturesAI.catalog_service.service;

import com.picturesAI.catalog_service.api.dto.ImageResponse;
import com.picturesAI.catalog_service.domain.CatalogImage;
import com.picturesAI.catalog_service.repository.CatalogImageRepository;
import com.picturesAI.catalog_service.repository.CatalogImageTagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class RecommendationService {

    static final int TOP_TAGS_LIMIT = 10;
    static final int MIN_TAGS_FOR_PERSONALIZATION = 3;

    private final CatalogImageRepository images;
    private final CatalogImageTagRepository imageTags;
    private final CatalogQueryService queryService;

    public RecommendationService(CatalogImageRepository images,
                                 CatalogImageTagRepository imageTags,
                                 CatalogQueryService queryService) {
        this.images = images;
        this.imageTags = imageTags;
        this.queryService = queryService;
    }

    public List<ImageResponse> recommend(UUID userId, int limit) {
        if (userId == null) {
            return fallback(limit);
        }
        List<String> topTags = imageTags.findTopTagsByUserId(userId, TOP_TAGS_LIMIT).stream()
                .map(row -> (String) row[0])
                .toList();
        if (topTags.size() < MIN_TAGS_FOR_PERSONALIZATION) {
            return fallback(limit);
        }
        List<UUID> ids = images.findRecommendedIds(topTags, userId, limit);
        if (ids.isEmpty()) {
            return fallback(limit);
        }
        return toOrderedResponses(ids);
    }

    private List<ImageResponse> fallback(int limit) {
        List<UUID> ids = images.findRecentPublicWithTags(limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        return toOrderedResponses(ids);
    }

    private List<ImageResponse> toOrderedResponses(List<UUID> ids) {
        List<CatalogImage> loaded = new ArrayList<>(images.findByIds(ids));
        loaded.sort((a, b) -> Integer.compare(ids.indexOf(a.getId()), ids.indexOf(b.getId())));
        return queryService.toImageResponses(loaded);
    }
}