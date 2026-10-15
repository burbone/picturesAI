package com.picturesAI.catalog_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "catalog_users")
@Getter
@Setter
public class CatalogUser {

    @Id
    private UUID id;

    @Column(name = "keycloak_id")
    private UUID keycloakId;

    @Column(length = 50)
    private String username;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "avatar_url", columnDefinition = "text")
    private String avatarUrl;

    @Column(nullable = false)
    private boolean anonymized = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}