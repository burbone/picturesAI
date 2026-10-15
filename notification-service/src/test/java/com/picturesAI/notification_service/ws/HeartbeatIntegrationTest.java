package com.picturesAI.notification_service.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.notification_service.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.Disposable;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class HeartbeatIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void heartbeatProps(DynamicPropertyRegistry registry) {
        registry.add("notification.ws.heartbeat-interval-seconds", () -> "1");
    }

    @LocalServerPort
    int port;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    ObjectMapper objectMapper;

    private Jwt validJwt(UUID userId) {
        return Jwt.withTokenValue("valid")
                .header("alg", "RS256")
                .subject(userId.toString())
                .claim("preferred_username", "alice")
                .claim("realm_access", Map.of("roles", List.of("user")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(120))
                .build();
    }

    @Test
    void heartbeatPingsActiveSession() throws Exception {
        UUID userId = UUID.randomUUID();
        when(jwtDecoder.decode("valid")).thenReturn(validJwt(userId));

        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws/notifications?token=valid");

        List<String> received = Collections.synchronizedList(new ArrayList<>());

        Disposable sub = client.execute(uri, session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(received::add)
                        .then()).subscribe();

        Thread.sleep(3500);
        sub.dispose();

        long pingCount = received.stream()
                .map(text -> {
                    try {
                        return objectMapper.readTree(text);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(node -> node != null && "ping".equals(node.path("type").asText()))
                .count();

        assertThat(pingCount).as("expected at least 2 pings in 3.5s with 1s interval").isGreaterThanOrEqualTo(2);

        for (String text : received) {
            JsonNode node = objectMapper.readTree(text);
            assertThat(node.path("type").asText()).isEqualTo("ping");
        }
    }
}