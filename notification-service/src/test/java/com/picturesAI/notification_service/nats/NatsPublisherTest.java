package com.picturesAI.notification_service.nats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.notification_service.config.NatsProperties;
import com.picturesAI.notification_service.dto.NotificationMessage;
import com.picturesAI.notification_service.dto.NotificationType;
import com.picturesAI.notification_service.metrics.NotificationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NatsPublisherTest {

    private Connection connection;
    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;
    private NatsPublisher publisher;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();
        NatsProperties props = new NatsProperties(
                "nats://localhost:4222", "u", "p", "notification.push");
        publisher = new NatsPublisher(connection, props, objectMapper, new NotificationMetrics(meterRegistry));
    }

    @Test
    void publishesSerializedMessageToConfiguredSubject() throws Exception {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        NotificationMessage msg = new NotificationMessage(
                NotificationType.GENERATION_COMPLETED,
                generationId,
                userId,
                Map.of("imageUrl", "http://img/x.png", "provider", "openai"));

        publisher.publish(msg);

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(connection).publish(eq("notification.push"), bodyCaptor.capture());

        JsonNode node = objectMapper.readTree(bodyCaptor.getValue());
        assertThat(node.get("type").asText()).isEqualTo("generation.completed");
        assertThat(node.get("generationId").asText()).isEqualTo(generationId.toString());
        assertThat(node.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(node.get("payload").get("imageUrl").asText()).isEqualTo("http://img/x.png");
        assertThat(node.get("payload").get("provider").asText()).isEqualTo("openai");

        assertThat(meterRegistry.get("nats_published_total")
                .tag("type", "generation.completed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void serializesNullGenerationIdForUserChannelEvents() throws Exception {
        UUID userId = UUID.randomUUID();
        NotificationMessage msg = new NotificationMessage(
                NotificationType.USER_DELETED,
                null,
                userId,
                Map.of("keycloakId", "kc-1"));

        publisher.publish(msg);

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(connection).publish(eq("notification.push"), bodyCaptor.capture());

        JsonNode node = objectMapper.readTree(bodyCaptor.getValue());
        assertThat(node.get("type").asText()).isEqualTo("user.deleted");
        assertThat(node.get("generationId").isNull()).isTrue();
        assertThat(node.get("userId").asText()).isEqualTo(userId.toString());
    }

    @Test
    void serializesGenerationFailedAndCancelledTypes() throws Exception {
        UUID gid = UUID.randomUUID();
        UUID uid = UUID.randomUUID();

        publisher.publish(new NotificationMessage(
                NotificationType.GENERATION_FAILED, gid, uid, Map.of("errorMessage", "boom")));
        publisher.publish(new NotificationMessage(
                NotificationType.GENERATION_CANCELLED, gid, uid, Map.of()));

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(connection, org.mockito.Mockito.times(2))
                .publish(eq("notification.push"), bodyCaptor.capture());

        var bodies = bodyCaptor.getAllValues();
        assertThat(objectMapper.readTree(bodies.get(0)).get("type").asText())
                .isEqualTo("generation.failed");
        assertThat(objectMapper.readTree(bodies.get(1)).get("type").asText())
                .isEqualTo("generation.cancelled");
    }
}