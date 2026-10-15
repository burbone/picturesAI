package com.picturesAI.catalog_service.repository;

import com.picturesAI.catalog_service.domain.CatalogImageTag;
import com.picturesAI.catalog_service.domain.CatalogImageTagId;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CatalogImageTagRepository extends Repository<CatalogImageTag, CatalogImageTagId> {

    CatalogImageTag save(CatalogImageTag tag);

    @Modifying
    @Query("DELETE FROM CatalogImageTag t WHERE t.id.imageId = :imageId")
    void deleteByImageId(@Param("imageId") UUID imageId);

    @Query("SELECT t FROM CatalogImageTag t WHERE t.id.imageId = :imageId")
    List<CatalogImageTag> findByImageId(@Param("imageId") UUID imageId);

    @Query("SELECT t FROM CatalogImageTag t WHERE t.id.imageId IN :imageIds")
    List<CatalogImageTag> findByImageIds(@Param("imageIds") Collection<UUID> imageIds);

    @Query(value = """
            SELECT tag AS tag, COUNT(*) AS total
            FROM catalog_image_tags
            GROUP BY tag
            ORDER BY COUNT(*) DESC, tag ASC
            """, nativeQuery = true)
    List<TagCountProjection> findTagCounts();

    @Query(value = """
            SELECT DISTINCT tag FROM catalog_image_tags ORDER BY tag ASC
            """, nativeQuery = true)
    List<String> findAllTags();

    @Query(value = """
            SELECT cit.tag AS tag, COUNT(*) AS cnt
            FROM catalog_image_tags cit
            JOIN catalog_images ci ON ci.id = cit.image_id
            WHERE ci.user_id = :userId
            GROUP BY cit.tag
            ORDER BY COUNT(*) DESC, cit.tag ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findTopTagsByUserId(@Param("userId") UUID userId,
                                       @Param("limit") int limit);

    interface TagCountProjection {
        String getTag();

        Long getTotal();
    }
}