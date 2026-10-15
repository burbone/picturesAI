package com.picturesAI.catalog_service.api;

import com.picturesAI.catalog_service.domain.CatalogImage;

import java.util.List;

public final class CursorSupport {

    private CursorSupport() {
    }

    public static CursorPage<CatalogImage> toPage(List<CatalogImage> rows,
                                                  int requestedSize,
                                                  CursorCodec codec) {
        boolean hasMore = rows.size() > requestedSize;
        List<CatalogImage> items = hasMore ? rows.subList(0, requestedSize) : rows;
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            CatalogImage last = items.get(items.size() - 1);
            nextCursor = codec.encode(last.getCompletedAt(), last.getId());
        }
        return new CursorPage<>(items, nextCursor, hasMore);
    }
}