package com.picturesAI.tagging_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "generation_tags")
@Getter
@Setter
@NoArgsConstructor
public class GenerationTag {

    @EmbeddedId
    private GenerationTagId id;

    @Column(name = "weight", nullable = false, precision = 3, scale = 2)
    private BigDecimal weight;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}