package com.picturesAI.catalog_service.service;

import com.picturesAI.catalog_service.api.CursorCodec;
import com.picturesAI.catalog_service.api.CursorPage;
import com.picturesAI.catalog_service.api.ImageNotFoundException;
import com.picturesAI.catalog_service.api.dto.AuthorResponse;
import com.picturesAI.catalog_service.api.dto.ImageResponse;
import com.picturesAI.catalog_service.api.dto.TagCountResponse;
import com.picturesAI.catalog_service.domain.CatalogImage;
import com.picturesAI.catalog_service.domain.CatalogUser;
import com.picturesAI.catalog_service.repository.CatalogImageRepository;
import com.picturesAI.catalog_service.repository.CatalogImageTagRepository;
import com.picturesAI.catalog_service.repository.CatalogUserRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CatalogQueryService {

    private static final int FETCH_OVERHEAD = 1;
    private static final int CACHEABLE_FEED_LIMIT = 20;

    private final CatalogImageRepository images;
    private final CatalogImageTagRepository imageTags;
    private final CatalogUserRepository users;
    private final PresignedUrlService presigner;
    private final CursorCodec codec;

    public CatalogQueryService(CatalogImageRepository images,
                               CatalogImageTagRepository imageTags,
                               CatalogUserRepository users,
                               PresignedUrlService presigner,
                               CursorCodec codec) {
        this.images = images;
        this.imageTags = imageTags;
        this.users = users;
        this.presigner = presigner;
        this.codec = codec;
    }

    @Cacheable(
            value = "feed",
            key = "'page:' + #limit",
            condition = "#cursor == null && #limit == " + CACHEABLE_FEED_LIMIT)
    public CursorPage<ImageResponse> getFeed(String cursor, int limit) {
        CursorCodec.Cursor c = codec.decode(cursor);
        List<CatalogImage> rows = images.findFeed(
                c == null ? null : c.completedAt(),
                c == null ? null : c.id(),
                limit + FETCH_OVERHEAD);
        return buildPage(rows, limit);
    }

    public CursorPage<ImageResponse> getImagesByTag(String tag, String cursor, int limit) {
        CursorCodec.Cursor c = codec.decode(cursor);
        List<CatalogImage> rows = images.findByTag(
                tag,
                c == null ? null : c.completedAt(),
                c == null ? null : c.id(),
                limit + FETCH_OVERHEAD);
        return buildPage(rows, limit);
    }

    public CursorPage<ImageResponse> search(String q, String cursor, int limit) {
        CursorCodec.Cursor c = codec.decode(cursor);
        List<CatalogImage> rows = images.search(
                q,
                c == null ? null : c.completedAt(),
                c == null ? null : c.id(),
                limit + FETCH_OVERHEAD);
        return buildPage(rows, limit);
    }

    public CursorPage<ImageResponse> getUserGenerations(UUID userId, String cursor, int limit) {
        CursorCodec.Cursor c = codec.decode(cursor);
        List<CatalogImage> rows = images.findByUser(
                userId,
                c == null ? null : c.completedAt(),
                c == null ? null : c.id(),
                limit + FETCH_OVERHEAD);
        return buildPage(rows, limit);
    }

    public ImageResponse getImage(UUID id) {
        CatalogImage image = images.findById(id)
                .orElseThrow(() -> new ImageNotFoundException(id));
        CatalogUser author = image.getUserId() == null
                ? null
                : users.findById(image.getUserId()).orElse(null);
        List<String> tags = imageTags.findByImageId(id).stream()
                .map(t -> t.getId().getTag())
                .toList();
        return toResponse(image, author, tags);
    }

    @Cacheable(value = "tags")
    public List<TagCountResponse> listTags() {
        return imageTags.findTagCounts().stream()
                .map(p -> new TagCountResponse(p.getTag(), p.getTotal() == null ? 0L : p.getTotal()))
                .toList();
    }

    public List<ImageResponse> toImageResponses(List<CatalogImage> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        Map<UUID, CatalogUser> authorsById = loadAuthors(source);
        Map<UUID, List<String>> tagsByImageId = loadTags(source);
        return source.stream()
                .map(img -> toResponse(
                        img,
                        img.getUserId() == null ? null : authorsById.get(img.getUserId()),
                        tagsByImageId.getOrDefault(img.getId(), List.of())))
                .toList();
    }

    private CursorPage<ImageResponse> buildPage(List<CatalogImage> rows, int limit) {
        boolean hasMore = rows.size() > limit;
        List<CatalogImage> kept = hasMore ? rows.subList(0, limit) : rows;
        if (kept.isEmpty()) {
            return new CursorPage<>(List.of(), null, false);
        }

        Map<UUID, CatalogUser> authorsById = loadAuthors(kept);
        Map<UUID, List<String>> tagsByImageId = loadTags(kept);

        List<ImageResponse> items = kept.stream()
                .map(img -> toResponse(
                        img,
                        img.getUserId() == null ? null : authorsById.get(img.getUserId()),
                        tagsByImageId.getOrDefault(img.getId(), List.of())))
                .toList();

        String nextCursor = null;
        if (hasMore) {
            CatalogImage last = kept.get(kept.size() - 1);
            nextCursor = codec.encode(last.getCompletedAt(), last.getId());
        }
        return new CursorPage<>(items, nextCursor, hasMore);
    }

    private Map<UUID, CatalogUser> loadAuthors(List<CatalogImage> rows) {
        Set<UUID> ids = rows.stream()
                .map(CatalogImage::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.findByIds(ids).stream()
                .collect(Collectors.toMap(CatalogUser::getId, u -> u));
    }

    private Map<UUID, List<String>> loadTags(List<CatalogImage> rows) {
        Collection<UUID> ids = rows.stream()
                .map(CatalogImage::getId)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return imageTags.findByImageIds(ids).stream()
                .collect(Collectors.groupingBy(
                        t -> t.getId().getImageId(),
                        Collectors.mapping(t -> t.getId().getTag(), Collectors.toList())));
    }

    private ImageResponse toResponse(CatalogImage image, CatalogUser author, List<String> tags) {
        String imageUrl = presigner.presign(image.getOriginalsBucket(), image.getOriginalsKey());
        String thumbnailUrl = presigner.presign(image.getThumbnailsBucket(), image.getThumbnailsKey());
        AuthorResponse authorResponse = author == null
                ? null
                : new AuthorResponse(
                author.getId(),
                author.getUsername(),
                author.getDisplayName(),
                author.getAvatarUrl(),
                author.isAnonymized());
        return new ImageResponse(
                image.getId(),
                image.getUserId(),
                image.getPrompt(),
                imageUrl,
                thumbnailUrl,
                image.getProvider(),
                image.getCompletedAt(),
                tags,
                authorResponse);
    }
}