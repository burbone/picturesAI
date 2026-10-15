package com.picturesAI.catalog_service.api;

import com.picturesAI.catalog_service.api.dto.ImageResponse;
import com.picturesAI.catalog_service.api.dto.TagCountResponse;
import com.picturesAI.catalog_service.service.CatalogQueryService;
import com.picturesAI.catalog_service.service.RecommendationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Validated
public class CatalogController {

    private static final int MAX_LIMIT = 100;
    private static final String DEFAULT_LIMIT = "20";

    private final CatalogQueryService query;
    private final RecommendationService recommendations;

    public CatalogController(CatalogQueryService query,
                             RecommendationService recommendations) {
        this.query = query;
        this.recommendations = recommendations;
    }

    @GetMapping("/feed")
    public CursorPage<ImageResponse> feed(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = DEFAULT_LIMIT) @Min(1) @Max(MAX_LIMIT) int limit) {
        return query.getFeed(cursor, limit);
    }

    @GetMapping("/images/{id}")
    public ImageResponse image(@PathVariable UUID id) {
        return query.getImage(id);
    }

    @GetMapping("/tags")
    public List<TagCountResponse> tags() {
        return query.listTags();
    }

    @GetMapping("/tags/{tag}/images")
    public CursorPage<ImageResponse> byTag(
            @PathVariable String tag,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = DEFAULT_LIMIT) @Min(1) @Max(MAX_LIMIT) int limit) {
        return query.getImagesByTag(tag, cursor, limit);
    }

    @GetMapping("/search")
    public CursorPage<ImageResponse> search(
            @RequestParam(name = "q") @NotBlank String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = DEFAULT_LIMIT) @Min(1) @Max(MAX_LIMIT) int limit) {
        return query.search(q, cursor, limit);
    }

    @GetMapping("/users/{id}/generations")
    public CursorPage<ImageResponse> userGenerations(
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = DEFAULT_LIMIT) @Min(1) @Max(MAX_LIMIT) int limit) {
        return query.getUserGenerations(id, cursor, limit);
    }

    @GetMapping("/recommendations")
    public List<ImageResponse> recommendations(
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @RequestParam(defaultValue = DEFAULT_LIMIT) @Min(1) @Max(50) int limit) {
        return recommendations.recommend(userId, limit);
    }
}