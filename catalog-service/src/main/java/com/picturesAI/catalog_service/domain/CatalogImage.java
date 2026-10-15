package com.picturesAI.catalog_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "catalog_images")
@Getter
@Setter
public class CatalogImage {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, columnDefinition = "text")
    private String prompt;

    @Column(name = "originals_bucket", nullable = false, length = 255)
    private String originalsBucket;

    @Column(name = "originals_key", nullable = false, length = 512)
    private String originalsKey;

    @Column(name = "thumbnails_bucket", nullable = false, length = 255)
    private String thumbnailsBucket;

    @Column(name = "thumbnails_key", nullable = false, length = 512)
    private String thumbnailsKey;

    @Column(length = 100)
    private String provider;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = true;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}