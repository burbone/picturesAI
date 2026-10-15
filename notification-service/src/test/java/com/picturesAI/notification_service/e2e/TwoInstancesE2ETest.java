package com.picturesAI.notification_service.e2e;

import com.picturesAI.notification_service.NotificationServiceApplication;
import com.picturesAI.notification_service.containers.KafkaTestContainer;
import com.picturesAI.notification_service.containers.NatsTestContainer;
import com.picturesAI.notification_service.integration.GenerationAccessResult;
import com.picturesAI.notification_service.integration.GenerationServiceClient;
import com.picturesAI.notification_service.integration.GenerationStatusResult;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TwoInstancesE2ETest {

    static KafkaTestContainer kafka = new KafkaTestContainer();
    static NatsTestContainer nats = new NatsTestContainer();

    static ConfigurableApplicationContext instanceA;
    static ConfigurableApplicationContext instanceB;
    static int portA;
    static int portB;

    static final UUID USER_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID USER_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeAll
    static void start() throws InterruptedException {
        kafka.start();
        nats.start();

        instanceA = startInstance();
        instanceB = startInstance();

        portA = ((WebServerApplicationContext) instanceA).getWebServer().getPort();
        portB = ((WebServerApplicationContext) instanceB).getWebServer().getPort();

        Thread.sleep(8000);
    }

    @AfterAll
    static void stop() {
        if (instanceA != null) instanceA.close();
        if (instanceB != null) instanceB.close();
        nats.stop();
        kafka.stop();
    }

    static ConfigurableApplicationContext startInstance() {
        return new SpringApplicationBuilder(NotificationServiceApplication.class, TestOverrides.class)
                .profiles("test")
                .web(WebApplicationType.REACTIVE)
                .run(
                        "--server.port=0",
                        "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                        "--spring.kafka.listener.auto-startup=true",
                        "--nats.url=" + nats.connectionUrl(),
                        "--nats.username=" + NatsTestContainer.USER,
                        "--nats.password=" + NatsTestContainer.PASS,
                        "--management.tracing.enabled=false",
                        "--management.otlp.tracing.endpoint=",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--logging.level.com.picturesAI.notification_service=INFO"
                );
    }

    @Configuration
    public static class TestOverrides {

        @Bean
        @Primary
        public JwtDecoder testJwtDecoder() {
            JwtDecoder decoder = mock(JwtDecoder.class);
            when(decoder.decode("t1")).thenReturn(jwt(USER_1));
            when(decoder.decode("t2")).thenReturn(jwt(USER_2));
            return decoder;
        }

        @Bean
        @Primary
        public GenerationServiceClient testGenerationServiceClient() {
            GenerationServiceClient client = mock(GenerationServiceClient.class);
            when(client.fetchStatus(any(), any(), any(), any()))
                    .thenReturn(Mono.just(new GenerationStatusResult(GenerationAccessResult.OK, null)));
            return client;
        }

        private static Jwt jwt(UUID userId) {
            return Jwt.withTokenValue("dummy")
                    .header("alg", "RS256")
                    .subject(userId.toString())
                    .claim("preferred_username", "test")
                    .claim("realm_access", Map.of("roles", List.of("user")))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        }
    }

    static class WsProbe implements AutoCloseable {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        private final Disposable sub;

        WsProbe(String url) {
            WebSocketClient client = new ReactorNettyWebSocketClient();
            this.sub = client.execute(URI.create(url), session -> {
                session.closeStatus().subscribe(closed::complete);
                return session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(messages::add)
                        .then();
            }).subscribe();
        }

        @Override
        public void close() {
            sub.dispose();
        }
    }

    static void publishCompleted(UUID userId, UUID generationId) {
        String payload = "{\"generationId\":\"" + generationId + "\",\"userId\":\"" + userId +
                "\",\"imageUrl\":\"http://img/x.png\",\"provider\":\"openai\"}";
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            producer.send(new ProducerRecord<>("generation.completed", generationId.toString(), payload));
            producer.flush();
        }
    }

    static void awaitMessages(List<String> list, int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (list.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    @Test
    void broadcastsToAllInstancesAndDeliversOnlyToMatchingLocalSessions() throws Exception {
        WsProbe clientA1 = new WsProbe("ws://localhost:" + portA + "/ws/notifications?token=t1");
        WsProbe clientB1 = new WsProbe("ws://localhost:" + portB + "/ws/notifications?token=t1");
        WsProbe clientB2 = new WsProbe("ws://localhost:" + portB + "/ws/notifications?token=t2");

        Thread.sleep(2000);

        UUID genForUser1 = UUID.randomUUID();
        publishCompleted(USER_1, genForUser1);

        awaitMessages(clientA1.messages, 1, 8000);
        awaitMessages(clientB1.messages, 1, 8000);

        assertThat(clientA1.messages).hasSize(1);
        assertThat(clientA1.messages.get(0)).contains(genForUser1.toString()).contains("generation.completed");
        assertThat(clientB1.messages).hasSize(1);
        assertThat(clientB1.messages.get(0)).contains(genForUser1.toString());
        assertThat(clientB2.messages).as("USER_2 client on B must not receive USER_1 event").isEmpty();

        UUID genForUser2 = UUID.randomUUID();
        publishCompleted(USER_2, genForUser2);

        awaitMessages(clientB2.messages, 1, 8000);

        assertThat(clientB2.messages).hasSize(1);
        assertThat(clientB2.messages.get(0)).contains(genForUser2.toString());
        assertThat(clientA1.messages).as("USER_1 client must not get USER_2 event").hasSize(1);
        assertThat(clientB1.messages).as("USER_1 client on B must not get USER_2 event").hasSize(1);

        clientA1.close();
        clientB1.close();
        clientB2.close();
    }
}