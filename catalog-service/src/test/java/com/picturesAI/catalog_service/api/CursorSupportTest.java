package com.picturesAI.catalog_service.api;

import com.picturesAI.catalog_service.domain.CatalogImage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CursorSupportTest {

    private final CursorCodec codec = new CursorCodec();

    private CatalogImage image(String prompt, Instant completedAt) {
        CatalogImage img = new CatalogImage();
        img.setId(UUID.randomUUID());
        img.setPrompt(prompt);
        img.setCompletedAt(completedAt);
        return img;
    }

    @Test
    void exactPageHasNoCursorAndNoMore() {
        Instant base = Instant.parse("2025-01-01T00:00:00Z");
        List<CatalogImage> rows = new ArrayList<>();
        rows.add(image("a", base));
        rows.add(image("b", base.plus(1, ChronoUnit.HOURS)));

        CursorPage<CatalogImage> page = CursorSupport.toPage(rows, 2, codec);

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void extraRowMeansHasMoreAndCursorFromLastKept() {
        Instant base = Instant.parse("2025-01-01T00:00:00Z");
        List<CatalogImage> rows = new ArrayList<>();
        CatalogImage a = image("a", base.plus(2, ChronoUnit.HOURS));
        CatalogImage b = image("b", base.plus(1, ChronoUnit.HOURS));
        CatalogImage c = image("c", base);
        rows.add(a);
        rows.add(b);
        rows.add(c);

        CursorPage<CatalogImage> page = CursorSupport.toPage(rows, 2, codec);

        assertThat(page.items()).containsExactly(a, b);
        assertThat(page.hasMore()).isTrue();
        CursorCodec.Cursor decoded = codec.decode(page.nextCursor());
        assertThat(decoded.completedAt()).isEqualTo(b.getCompletedAt());
        assertThat(decoded.id()).isEqualTo(b.getId());
    }

    @Test
    void emptyRowsReturnsEmptyPageWithoutCursor() {
        CursorPage<CatalogImage> page = CursorSupport.toPage(List.of(), 20, codec);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void fewerThanRequestedHasNoMore() {
        List<CatalogImage> rows = List.of(image("only", Instant.now()));

        CursorPage<CatalogImage> page = CursorSupport.toPage(rows, 20, codec);

        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }
}