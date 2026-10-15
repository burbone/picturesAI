package com.picturesAI.notification_service.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.notification_service.dto.NotificationMessage;
import com.picturesAI.notification_service.dto.NotificationType;
import com.picturesAI.notification_service.nats.NatsPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KafkaNotificationConsumerTest {

    private NatsPublisher publisher;
    private KafkaNotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        publisher = mock(NatsPublisher.class);
        consumer = new KafkaNotificationConsumer(new ObjectMapper(), publisher);
    }

    @Test
    void onGenerationCompletedPublishesWithAllFields() {
        UUID gid = UUID.randomUUID();
        UUID uid = UUID.randomUUID();
        String json = "{\"generationId\":\"" + gid + "\",\"userId\":\"" + uid +
                "\",\"imageUrl\":\"http://img/x.png\",\"thumbnailUrl\":\"http://img/t.png\"," +
                "\"provider\":\"openai\",\"completedAt\":\"2026-01-01T00:00:00Z\"}";

        consumer.onGenerationCompleted(json);

        NotificationMessage msg = capturedMessage();
        assertThat(msg.type()).isEqualTo(NotificationType.GENERATION_COMPLETED);
        assertThat(msg.generationId()).isEqualTo(gid);
        assertThat(msg.userId()).isEqualTo(uid);
        assertThat(msg.payload()).containsEntry("imageUrl", "http://img/x.png");
        assertThat(msg.payload()).containsEntry("provider", "openai");
        assertThat(msg.payload()).containsEntry("thumbnailUrl", "http://img/t.png");
    }

    @Test
    void onGenerationFailedPublishes() {
        UUID gid = UUID.randomUUID();
        UUID uid = UUID.randomUUID();
        String json = "{\"generationId\":\"" + gid + "\",\"userId\":\"" + uid +
                "\",\"errorMessage\":\"boom\",\"failedAt\":\"2026-01-01T00:00:00Z\"}";

        consumer.onGenerationFailed(json);

        NotificationMessage msg = capturedMessage();
        assertThat(msg.type()).isEqualTo(NotificationType.GENERATION_FAILED);
        assertThat(msg.generationId()).isEqualTo(gid);
        assertThat(msg.payload()).containsEntry("errorMessage", "boom");
    }

    @Test
    void onGenerationCancelledPublishes() {
        UUID gid = UUID.randomUUID();
        UUID uid = UUID.randomUUID();
        String json = "{\"generationId\":\"" + gid + "\",\"userId\":\"" + uid +
                "\",\"cancelledAt\":\"2026-01-01T00:00:00Z\"}";

        consumer.onGenerationCancelled(json);

        NotificationMessage msg = capturedMessage();
        assertThat(msg.type()).isEqualTo(NotificationType.GENERATION_CANCELLED);
        assertThat(msg.generationId()).isEqualTo(gid);
    }

    @Test
    void onUserDeletedPublishesWithNullGenerationId() {
        UUID uid = UUID.randomUUID();
        String json = "{\"userId\":\"" + uid + "\",\"keycloakId\":\"kc-1\"," +
                "\"deletedAt\":\"2026-01-01T00:00:00Z\"}";

        consumer.onUserDeleted(json);

        NotificationMessage msg = capturedMessage();
        assertThat(msg.type()).isEqualTo(NotificationType.USER_DELETED);
        assertThat(msg.generationId()).isNull();
        assertThat(msg.userId()).isEqualTo(uid);
        assertThat(msg.payload()).containsEntry("keycloakId", "kc-1");
    }

    @Test
    void malformedJsonIsIgnored() {
        consumer.onGenerationCompleted("{not json");

        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingUserIdIsSkipped() {
        String json = "{\"generationId\":\"" + UUID.randomUUID() + "\",\"imageUrl\":\"x\"}";

        consumer.onGenerationCompleted(json);

        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonUuidUserIdIsSkipped() {
        String json = "{\"generationId\":\"" + UUID.randomUUID() + "\",\"userId\":\"not-a-uuid\"}";

        consumer.onGenerationCompleted(json);

        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonUuidGenerationIdResultsInNullButStillPublishes() {
        UUID uid = UUID.randomUUID();
        String json = "{\"generationId\":\"garbage\",\"userId\":\"" + uid + "\"}";

        consumer.onGenerationCompleted(json);

        NotificationMessage msg = capturedMessage();
        assertThat(msg.generationId()).isNull();
        assertThat(msg.userId()).isEqualTo(uid);
    }

    @Test
    void originalPayloadPreservedVerbatim() {
        UUID gid = UUID.randomUUID();
        UUID uid = UUID.randomUUID();
        String json = "{\"generationId\":\"" + gid + "\",\"userId\":\"" + uid +
                "\",\"extra\":{\"nested\":true},\"count\":42}";

        consumer.onGenerationCompleted(json);

        NotificationMessage msg = capturedMessage();
        assertThat(msg.payload()).containsEntry("count", 42);
        assertThat(msg.payload().get("extra")).isInstanceOf(java.util.Map.class);
    }

    private NotificationMessage capturedMessage() {
        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }
}