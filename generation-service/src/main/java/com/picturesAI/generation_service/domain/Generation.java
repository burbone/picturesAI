package com.picturesAI.generation_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "generations")
@Getter
@Setter
@NoArgsConstructor
public class Generation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "prompt", nullable = false, columnDefinition = "text")
    private String prompt;

    @Column(name = "negative_prompt", columnDefinition = "text")
    private String negativePrompt;

    @Column(name = "model", nullable = false, length = 50)
    private String model;

    @Column(name = "size", nullable = false, length = 20)
    private String size;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 10)
    private Tier tier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GenerationStatus status;

    @Column(name = "image_url", columnDefinition = "text")
    private String imageUrl;

    @Column(name = "thumbnail_url", columnDefinition = "text")
    private String thumbnailUrl;

    @Column(name = "provider", length = 30)
    private String provider;

    @Column(name = "provider_job_id", length = 100)
    private String providerJobId;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "last_error_message", columnDefinition = "text")
    private String lastErrorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "is_public", nullable = false)
    private boolean publicFlag;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (tier == null) {
            tier = Tier.FREE;
        }
        if (status == null) {
            status = GenerationStatus.QUEUED;
        }
    }
}