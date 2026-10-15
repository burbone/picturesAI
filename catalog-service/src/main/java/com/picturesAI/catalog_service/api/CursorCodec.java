package com.picturesAI.catalog_service.api;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class CursorCodec {

    private static final String SEPARATOR = "|";

    public String encode(Instant completedAt, UUID id) {
        String raw = completedAt.toString() + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("Malformed cursor");
        }
        int sep = raw.indexOf(SEPARATOR);
        if (sep <= 0 || sep == raw.length() - 1) {
            throw new InvalidCursorException("Malformed cursor");
        }
        String tsPart = raw.substring(0, sep);
        String idPart = raw.substring(sep + 1);
        try {
            return new Cursor(Instant.parse(tsPart), UUID.fromString(idPart));
        } catch (RuntimeException e) {
            throw new InvalidCursorException("Malformed cursor");
        }
    }

    public record Cursor(Instant completedAt, UUID id) {
    }
}