package com.picturesAI.tagging_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tag_vocabulary")
@Getter
@Setter
@NoArgsConstructor
public class TagVocabulary {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 50)
    private String name;

    @Column(name = "category", nullable = false, length = 30)
    private String category;

    @Column(name = "aliases", nullable = false, columnDefinition = "text[]")
    private String[] aliases;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (aliases == null) {
            aliases = new String[0];
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}