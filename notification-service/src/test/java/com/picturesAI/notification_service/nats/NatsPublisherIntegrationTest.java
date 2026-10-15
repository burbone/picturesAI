package com.picturesAI.notification_service.nats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.notification_service.config.NatsProperties;
import com.picturesAI.notification_service.containers.NatsTestContainer;
import com.picturesAI.notification_service.dto.NotificationMessage;
import com.picturesAI.notification_service.dto.NotificationType;
import com.picturesAI.notification_service.metrics.NotificationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NatsPublisherIntegrationTest {

    static GenericContainer<?> nats;

    @BeforeAll
    static void startNats() {
        nats = new NatsTestContainer();
        nats.start();
    }

    @AfterAll
    static void stopNats() {
        nats.stop();
    }

    private Options options() {
        String url = "nats://" + nats.getHost() + ":" + nats.getMappedPort(NatsTestContainer.CLIENT_PORT);
        return new Options.Builder()
                .server(url)
                .userInfo(NatsTestContainer.USER, NatsTestContainer.PASS)
                .connectionTimeout(Duration.ofSeconds(5))
                .build();
    }

    private NatsPublisher publisherFor(Connection conn) {
        String url = "nats://" + nats.getHost() + ":" + nats.getMappedPort(NatsTestContainer.CLIENT_PORT);
        NatsProperties props = new NatsProperties(
                url, NatsTestContainer.USER, NatsTestContainer.PASS, "notification.push");
        return new NatsPublisher(conn, props, new ObjectMapper(),
                new NotificationMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void publishesMessageThatSubscriberReceives() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        try (Connection subscriberConnection = Nats.connect(options());
             Connection publisherConnection = Nats.connect(options())) {

            CompletableFuture<Message> received = new CompletableFuture<>();
            Dispatcher dispatcher = subscriberConnection.createDispatcher(received::complete);
            dispatcher.subscribe("notification.push");

            NatsPublisher publisher = publisherFor(publisherConnection);

            UUID generationId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            publisher.publish(new NotificationMessage(
                    NotificationType.GENERATION_COMPLETED,
                    generationId,
                    userId,
                    Map.of("imageUrl", "http://img/x.png")));

            publisherConnection.flush(Duration.ofSeconds(2));

            Message msg = received.get(5, TimeUnit.SECONDS);
            JsonNode node = objectMapper.readTree(msg.getData());
            assertThat(node.get("type").asText()).isEqualTo("generation.completed");
            assertThat(node.get("generationId").asText()).isEqualTo(generationId.toString());
            assertThat(node.get("payload").get("imageUrl").asText()).isEqualTo("http://img/x.png");
        }
    }

    @Test
    void publishesUserDeletedWithNullGenerationId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        try (Connection subscriberConnection = Nats.connect(options());
             Connection publisherConnection = Nats.connect(options())) {

            CompletableFuture<Message> received = new CompletableFuture<>();
            Dispatcher dispatcher = subscriberConnection.createDispatcher(received::complete);
            dispatcher.subscribe("notification.push");

            NatsPublisher publisher = publisherFor(publisherConnection);

            UUID userId = UUID.randomUUID();
            publisher.publish(new NotificationMessage(
                    NotificationType.USER_DELETED, null, userId, Map.of()));

            publisherConnection.flush(Duration.ofSeconds(2));

            Message msg = received.get(5, TimeUnit.SECONDS);
            JsonNode node = objectMapper.readTree(msg.getData());
            assertThat(node.get("type").asText()).isEqualTo("user.deleted");
            assertThat(node.get("generationId").isNull()).isTrue();
        }
    }
}