package com.picturesAI.catalog_service.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    private final CursorCodec codec = new CursorCodec();

    @Test
    void encodeDecodeRoundTrip() {
        Instant ts = Instant.parse("2025-01-01T12:34:56.789Z");
        UUID id = UUID.randomUUID();

        String cursor = codec.encode(ts, id);
        CursorCodec.Cursor decoded = codec.decode(cursor);

        assertThat(decoded.completedAt()).isEqualTo(ts);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void encodeIsUrlSafe() {
        String cursor = codec.encode(Instant.now(), UUID.randomUUID());

        assertThat(cursor).doesNotContain("+", "/", "=");
    }

    @Test
    void decodeNullReturnsNull() {
        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode("")).isNull();
        assertThat(codec.decode("   ")).isNull();
    }

    @Test
    void decodeMalformedBase64Throws() {
        assertThatThrownBy(() -> codec.decode("!!!not-base64!!!"))
                .isInstanceOf(InvalidCursorException.class)
                .hasMessageContaining("Malformed cursor");
    }

    @Test
    void decodeMissingSeparatorThrows() {
        String raw = "no-separator-here";
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.decode(cursor))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void decodeInvalidTimestampThrows() {
        String raw = "not-a-time|" + UUID.randomUUID();
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.decode(cursor))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void decodeInvalidUuidThrows() {
        String raw = Instant.now() + "|not-a-uuid";
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.decode(cursor))
                .isInstanceOf(InvalidCursorException.class);
    }
}