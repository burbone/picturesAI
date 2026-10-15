package com.picturesAI.notification_service.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.notification_service.NotificationServiceApplication;
import com.picturesAI.notification_service.containers.KafkaTestContainer;
import com.picturesAI.notification_service.containers.NatsTestContainer;
import com.picturesAI.notification_service.integration.GenerationServiceClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = NotificationServiceApplication.class,
        properties = "spring.kafka.listener.auto-startup=true"
)
class KafkaToWebSocketE2ETest {

    static KafkaTestContainer kafka = new KafkaTestContainer();
    static NatsTestContainer nats = new NatsTestContainer();

    @BeforeAll
    static void startContainers() {
        kafka.start();
        nats.start();
    }

    @AfterAll
    static void stopContainers() {
        nats.stop();
        kafka.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
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

    private void sendKafka(String topic, String key, String value) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            producer.send(new ProducerRecord<>(topic, key, value));
            producer.flush();
        }
    }

    private CompletableFuture<String> openUserSocket(UUID userId) {
        when(jwtDecoder.decode("valid")).thenReturn(validJwt(userId));
        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws/notifications?token=valid");
        CompletableFuture<String> received = new CompletableFuture<>();
        Mono<Void> sessionMono = client.execute(uri, session ->
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(received::complete)
                        .then());
        Thread t = new Thread(() -> sessionMono.block(Duration.ofSeconds(15)));
        t.setDaemon(true);
        t.start();
        return received;
    }

    private CompletableFuture<CloseStatus> openUserSocketForClose(UUID userId) {
        when(jwtDecoder.decode("valid")).thenReturn(validJwt(userId));
        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws/notifications?token=valid");
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        Mono<Void> sessionMono = client.execute(uri, session ->
                session.closeStatus()
                        .doOnNext(closed::complete)
                        .then());
        Thread t = new Thread(() -> sessionMono.block(Duration.ofSeconds(15)));
        t.setDaemon(true);
        t.start();
        return closed;
    }

    @Test
    void generationCompletedFlowsFromKafkaToWebSocket() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();

        CompletableFuture<String> received = openUserSocket(userId);
        Thread.sleep(1500);

        String kafkaPayload = "{\"generationId\":\"" + generationId + "\",\"userId\":\"" + userId +
                "\",\"imageUrl\":\"http://img/x.png\",\"thumbnailUrl\":\"http://img/t.png\"," +
                "\"provider\":\"openai\",\"completedAt\":\"2026-01-01T00:00:00Z\"}";
        sendKafka("generation.completed", generationId.toString(), kafkaPayload);

        String body = received.get(15, TimeUnit.SECONDS);
        JsonNode node = new ObjectMapper().readTree(body);
        assertThat(node.get("type").asText()).isEqualTo("generation.completed");
        assertThat(node.get("generationId").asText()).isEqualTo(generationId.toString());
        assertThat(node.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(node.get("payload").get("imageUrl").asText()).isEqualTo("http://img/x.png");
        assertThat(node.get("payload").get("provider").asText()).isEqualTo("openai");
    }

    @Test
    void generationFailedFlowsFromKafkaToWebSocket() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();

        CompletableFuture<String> received = openUserSocket(userId);
        Thread.sleep(1500);

        String kafkaPayload = "{\"generationId\":\"" + generationId + "\",\"userId\":\"" + userId +
                "\",\"errorMessage\":\"provider timeout\",\"failedAt\":\"2026-01-01T00:00:00Z\"}";
        sendKafka("generation.failed", generationId.toString(), kafkaPayload);

        String body = received.get(15, TimeUnit.SECONDS);
        JsonNode node = new ObjectMapper().readTree(body);
        assertThat(node.get("type").asText()).isEqualTo("generation.failed");
        assertThat(node.get("payload").get("errorMessage").asText()).isEqualTo("provider timeout");
    }

    @Test
    void userDeletedClosesSocketWith1000() throws Exception {
        UUID userId = UUID.randomUUID();

        CompletableFuture<CloseStatus> closed = openUserSocketForClose(userId);
        Thread.sleep(1500);

        String kafkaPayload = "{\"userId\":\"" + userId + "\",\"keycloakId\":\"kc-1\"," +
                "\"deletedAt\":\"2026-01-01T00:00:00Z\"}";
        sendKafka("user.deleted", userId.toString(), kafkaPayload);

        CloseStatus status = closed.get(15, TimeUnit.SECONDS);
        assertThat(status.getCode()).isEqualTo(1000);
        assertThat(status.getReason()).isEqualTo("normal closure, account deleted");
    }
}