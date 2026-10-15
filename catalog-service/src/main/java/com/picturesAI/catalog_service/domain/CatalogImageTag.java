package com.picturesAI.catalog_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "catalog_image_tags")
@Getter
@Setter
public class CatalogImageTag {

    @EmbeddedId
    private CatalogImageTagId id;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal weight;

    public static CatalogImageTag of(UUID imageId, String tag) {
        return of(imageId, tag, null);
    }

    public static CatalogImageTag of(UUID imageId, String tag, BigDecimal weight) {
        CatalogImageTag e = new CatalogImageTag();
        e.setId(new CatalogImageTagId(imageId, tag));
        e.setWeight(weight == null ? new BigDecimal("0.50") : weight);
        return e;
    }
}