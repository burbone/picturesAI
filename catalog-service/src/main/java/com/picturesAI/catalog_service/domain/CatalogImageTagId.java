package com.picturesAI.catalog_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CatalogImageTagId implements Serializable {

    @Column(name = "image_id", nullable = false)
    private UUID imageId;

    @Column(name = "tag", nullable = false, length = 100)
    private String tag;
}