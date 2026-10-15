package com.picturesAI.notification_service.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.notification_service.AbstractIntegrationTest;
import com.picturesAI.notification_service.integration.GenerationAccessResult;
import com.picturesAI.notification_service.integration.GenerationServiceClient;
import com.picturesAI.notification_service.integration.GenerationStatusResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class WebSocketHandshakeIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    GenerationServiceClient generationServiceClient;

    @Autowired
    ObjectMapper objectMapper;

    private WebSocketClient client;

    @BeforeEach
    void setUp() {
        client = new ReactorNettyWebSocketClient();
    }

    private Jwt validJwt(UUID userId) {
        return Jwt.withTokenValue("valid")
                .header("alg", "RS256")
                .subject(userId.toString())
                .claim("preferred_username", "alice")
                .claim("realm_access", Map.of("roles", List.of("user")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private URI uri(String path, String token) {
        return URI.create("ws://localhost:" + port + path + "?token=" + token);
    }

    private CloseStatus connectAndCaptureClose(URI uri) {
        AtomicReference<CloseStatus> captured = new AtomicReference<>();
        client.execute(uri, session -> session.closeStatus()
                        .doOnNext(captured::set)
                        .then())
                .block(Duration.ofSeconds(5));
        return captured.get();
    }

    @Test
    void validTokenUserChannelOpensSession() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        when(jwtDecoder.decode("valid")).thenReturn(validJwt(userId));

        AtomicBoolean opened = new AtomicBoolean(false);
        AtomicReference<CloseStatus> closed = new AtomicReference<>();

        Disposable sub = client.execute(uri("/ws/notifications", "valid"), session -> {
            opened.set(true);
            return session.closeStatus().doOnNext(closed::set).then();
        }).subscribe();

        Thread.sleep(500);

        assertThat(opened).isTrue();
        assertThat(closed.get()).isNull();

        sub.dispose();
    }

    @Test
    void initialStatusDeliveredAsFirstMessage() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        when(jwtDecoder.decode("valid")).thenReturn(validJwt(userId));

        JsonNode payload = objectMapper.readTree("{\"id\":\"" + generationId +
                "\",\"status\":\"QUEUED\",\"tier\":\"FREE\"}");
        when(generationServiceClient.fetchStatus(any(), any(), any(), any()))
                .thenReturn(Mono.just(new GenerationStatusResult(GenerationAccessResult.OK, payload)));

        AtomicReference<String> first = new AtomicReference<>();

        Disposable sub = client.execute(uri("/ws/generations/" + generationId, "valid"), session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(text -> first.compareAndSet(null, text))
                        .then()).subscribe();

        Thread.sleep(2000);
        sub.dispose();

        assertThat(first.get()).isNotNull();
        JsonNode node = objectMapper.readTree(first.get());
        assertThat(node.get("type").asText()).isEqualTo("generation.queued");
        assertThat(node.get("generationId").asText()).isEqualTo(generationId.toString());
        assertThat(node.get("payload").get("status").asText()).isEqualTo("QUEUED");
    }

    @Test
    void invalidTokenClosesWith1008() {
        when(jwtDecoder.decode(anyString())).thenThrow(new JwtException("bad"));

        CloseStatus status = connectAndCaptureClose(uri("/ws/notifications", "bad"));

        assertThat(status).isNotNull();
        assertThat(status.getCode()).isEqualTo(1008);
        assertThat(status.getReason()).isEqualTo("unauthorized");
    }

    @Test
    void missingTokenClosesWith1008() {
        CloseStatus status = connectAndCaptureClose(uri("/ws/notifications", ""));

        assertThat(status).isNotNull();
        assertThat(status.getCode()).isEqualTo(1008);
        assertThat(status.getReason()).isEqualTo("unauthorized");
    }

    @Test
    void generationNotFoundClosesWith1008() {
        UUID userId = UUID.randomUUID();
        when(jwtDecoder.decode("valid")).thenReturn(validJwt(userId));
        when(generationServiceClient.fetchStatus(any(), any(), any(), any()))
                .thenReturn(Mono.just(new GenerationStatusResult(GenerationAccessResult.NOT_FOUND, null)));

        CloseStatus status = connectAndCaptureClose(uri("/ws/generations/" + UUID.randomUUID(), "valid"));

        assertThat(status).isNotNull();
        assertThat(status.getCode()).isEqualTo(1008);
        assertThat(status.getReason()).isEqualTo("not found");
    }

    @Test
    void generationForbiddenClosesWith1008() {
        UUID userId = UUID.randomUUID();
        when(jwtDecoder.decode("valid")).thenReturn(validJwt(userId));
        when(generationServiceClient.fetchStatus(any(), any(), any(), any()))
                .thenReturn(Mono.just(new GenerationStatusResult(GenerationAccessResult.FORBIDDEN, null)));

        CloseStatus status = connectAndCaptureClose(uri("/ws/generations/" + UUID.randomUUID(), "valid"));

        assertThat(status).isNotNull();
        assertThat(status.getCode()).isEqualTo(1008);
        assertThat(status.getReason()).isEqualTo("forbidden");
    }

    @Test
    void generationServiceDownClosesWith1011() {
        UUID userId = UUID.randomUUID();
        when(jwtDecoder.decode("valid")).thenReturn(validJwt(userId));
        when(generationServiceClient.fetchStatus(any(), any(), any(), any()))
                .thenReturn(Mono.just(new GenerationStatusResult(GenerationAccessResult.INTERNAL_ERROR, null)));

        CloseStatus status = connectAndCaptureClose(uri("/ws/generations/" + UUID.randomUUID(), "valid"));

        assertThat(status).isNotNull();
        assertThat(status.getCode()).isEqualTo(1011);
        assertThat(status.getReason()).isEqualTo("internal error");
    }
}