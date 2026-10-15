package com.picturesAI.catalog_service.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogImageTagIdTest {

    @Test
    void equalsAndHashCodeByBothFields() {
        UUID id = UUID.randomUUID();
        CatalogImageTagId a = new CatalogImageTagId(id, "cat");
        CatalogImageTagId b = new CatalogImageTagId(id, "cat");
        CatalogImageTagId differentTag = new CatalogImageTagId(id, "dog");
        CatalogImageTagId differentImage = new CatalogImageTagId(UUID.randomUUID(), "cat");

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(differentTag);
        assertThat(a).isNotEqualTo(differentImage);
    }

    @Test
    void usableAsHashSetKey() {
        UUID id = UUID.randomUUID();
        Set<CatalogImageTagId> set = new HashSet<>();
        set.add(new CatalogImageTagId(id, "cat"));
        set.add(new CatalogImageTagId(id, "cat"));
        set.add(new CatalogImageTagId(id, "dog"));

        assertThat(set).hasSize(2);
    }
}