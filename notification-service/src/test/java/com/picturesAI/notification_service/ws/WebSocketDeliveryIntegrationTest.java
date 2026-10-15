package com.picturesAI.notification_service.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.notification_service.NotificationServiceApplication;
import com.picturesAI.notification_service.containers.NatsTestContainer;
import com.picturesAI.notification_service.dto.NotificationMessage;
import com.picturesAI.notification_service.dto.NotificationType;
import com.picturesAI.notification_service.integration.GenerationServiceClient;
import com.picturesAI.notification_service.nats.NatsPublisher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = NotificationServiceApplication.class
)
class WebSocketDeliveryIntegrationTest {

    static NatsTestContainer nats = new NatsTestContainer();

    @BeforeAll
    static void startNats() {
        nats.start();
    }

    @AfterAll
    static void stopNats() {
        nats.stop();
    }

    @DynamicPropertySource
    static void natsProps(DynamicPropertyRegistry registry) {
        registry.add("nats.url", nats::connectionUrl);
        registry.add("nats.username", () -> NatsTestContainer.USER);
        registry.add("nats.password", () -> NatsTestContainer.PASS);
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    GenerationServiceClient generationServiceClient;

    @LocalServerPort
    int port;

    @Autowired
    NatsPublisher publisher;

    @Autowired
    ObjectMapper objectMapper;

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

    @Test
    void notificationDeliveredToUserChannel() throws Exception {
        UUID userId = UUID.randomUUID();
        when(jwtDecoder.decode("valid")).thenReturn(validJwt(userId));

        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws/notifications?token=valid");
        CompletableFuture<String> received = new CompletableFuture<>();

        Mono<Void> sessionMono = client.execute(uri, session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(received::complete)
                        .then());

        Thread subscriber = new Thread(() -> sessionMono.block(Duration.ofSeconds(10)));
        subscriber.setDaemon(true);
        subscriber.start();

        Thread.sleep(700);

        UUID generationId = UUID.randomUUID();
        publisher.publish(new NotificationMessage(
                NotificationType.GENERATION_COMPLETED,
                generationId,
                userId,
                Map.of("imageUrl", "http://img/x.png")));

        String body = received.get(5, TimeUnit.SECONDS);
        JsonNode node = objectMapper.readTree(body);
        assertThat(node.get("type").asText()).isEqualTo("generation.completed");
        assertThat(node.get("generationId").asText()).isEqualTo(generationId.toString());
        assertThat(node.get("userId").asText()).isEqualTo(userId.toString());
    }

    @Test
    void notificationNotDeliveredToOtherUser() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        when(jwtDecoder.decode("valid")).thenReturn(validJwt(stranger));

        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws/notifications?token=valid");
        CompletableFuture<String> received = new CompletableFuture<>();

        Mono<Void> sessionMono = client.execute(uri, session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(received::complete)
                        .then());

        Thread subscriber = new Thread(() -> sessionMono.block(Duration.ofSeconds(10)));
        subscriber.setDaemon(true);
        subscriber.start();

        Thread.sleep(700);

        publisher.publish(new NotificationMessage(
                NotificationType.GENERATION_COMPLETED,
                UUID.randomUUID(),
                owner,
                Map.of()));

        try {
            received.get(1500, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException expected) {
        }

        assertThat(received.isDone()).isFalse();
    }

    @Test
    void userDeletedClosesUserSocketWith1000() throws Exception {
        UUID userId = UUID.randomUUID();
        when(jwtDecoder.decode("valid")).thenReturn(validJwt(userId));

        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws/notifications?token=valid");
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        Mono<Void> sessionMono = client.execute(uri, session ->
                session.closeStatus()
                        .doOnNext(closed::complete)
                        .then());

        Thread subscriber = new Thread(() -> sessionMono.block(Duration.ofSeconds(10)));
        subscriber.setDaemon(true);
        subscriber.start();

        Thread.sleep(700);

        publisher.publish(new NotificationMessage(
                NotificationType.USER_DELETED,
                null,
                userId,
                Map.of("keycloakId", "kc-1")));

        CloseStatus status = closed.get(5, TimeUnit.SECONDS);
        assertThat(status.getCode()).isEqualTo(1000);
        assertThat(status.getReason()).isEqualTo("normal closure, account deleted");
    }
}