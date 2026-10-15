package com.picturesAI.catalog_service.repository;

import com.picturesAI.catalog_service.domain.CatalogImage;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogImageRepository extends Repository<CatalogImage, UUID> {

    CatalogImage save(CatalogImage image);

    Optional<CatalogImage> findById(UUID id);

    @Query(value = """
            SELECT * FROM catalog_images
            WHERE (CAST(:cursorTs AS timestamp) IS NULL
                   OR (completed_at, id) < (CAST(:cursorTs AS timestamp), CAST(:cursorId AS uuid)))
            ORDER BY completed_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<CatalogImage> findFeed(@Param("cursorTs") Instant cursorTs,
                                @Param("cursorId") UUID cursorId,
                                @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM catalog_images
            WHERE user_id = :userId
              AND (CAST(:cursorTs AS timestamp) IS NULL
                   OR (completed_at, id) < (CAST(:cursorTs AS timestamp), CAST(:cursorId AS uuid)))
            ORDER BY completed_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<CatalogImage> findByUser(@Param("userId") UUID userId,
                                  @Param("cursorTs") Instant cursorTs,
                                  @Param("cursorId") UUID cursorId,
                                  @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM catalog_images
            WHERE to_tsvector('simple', prompt) @@ plainto_tsquery('simple', :q)
              AND (CAST(:cursorTs AS timestamp) IS NULL
                   OR (completed_at, id) < (CAST(:cursorTs AS timestamp), CAST(:cursorId AS uuid)))
            ORDER BY completed_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<CatalogImage> search(@Param("q") String q,
                              @Param("cursorTs") Instant cursorTs,
                              @Param("cursorId") UUID cursorId,
                              @Param("limit") int limit);

    @Query(value = """
            SELECT ci.* FROM catalog_images ci
            JOIN catalog_image_tags cit ON cit.image_id = ci.id
            WHERE cit.tag = :tag
              AND (CAST(:cursorTs AS timestamp) IS NULL
                   OR (ci.completed_at, ci.id) < (CAST(:cursorTs AS timestamp), CAST(:cursorId AS uuid)))
            ORDER BY ci.completed_at DESC, ci.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<CatalogImage> findByTag(@Param("tag") String tag,
                                 @Param("cursorTs") Instant cursorTs,
                                 @Param("cursorId") UUID cursorId,
                                 @Param("limit") int limit);

    @Query(value = """
            SELECT ci.id FROM catalog_images ci
            JOIN catalog_image_tags cit ON cit.image_id = ci.id
            WHERE cit.tag IN (:tags)
              AND ci.user_id IS NOT NULL
              AND ci.user_id != :userId
              AND ci.is_public = true
            GROUP BY ci.id
            ORDER BY COUNT(DISTINCT cit.tag) DESC, MAX(ci.completed_at) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findRecommendedIds(@Param("tags") List<String> tags,
                                  @Param("userId") UUID userId,
                                  @Param("limit") int limit);

    @Query(value = """
            SELECT ci.id FROM catalog_images ci
            WHERE ci.is_public = true
              AND EXISTS (SELECT 1 FROM catalog_image_tags cit WHERE cit.image_id = ci.id)
            ORDER BY ci.completed_at DESC, ci.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findRecentPublicWithTags(@Param("limit") int limit);

    @Query(value = """
            SELECT * FROM catalog_images
            WHERE id IN :ids
            """, nativeQuery = true)
    List<CatalogImage> findByIds(@Param("ids") List<UUID> ids);
}