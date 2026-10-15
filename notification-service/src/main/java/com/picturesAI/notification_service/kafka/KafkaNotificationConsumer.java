package com.picturesAI.notification_service.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.notification_service.dto.NotificationMessage;
import com.picturesAI.notification_service.dto.NotificationType;
import com.picturesAI.notification_service.nats.NatsPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class KafkaNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaNotificationConsumer.class);

    private final ObjectMapper objectMapper;
    private final NatsPublisher publisher;

    public KafkaNotificationConsumer(ObjectMapper objectMapper, NatsPublisher publisher) {
        this.objectMapper = objectMapper;
        this.publisher = publisher;
    }

    @KafkaListener(topics = "generation.completed", groupId = "notification-service")
    public void onGenerationCompleted(String raw) {
        handle(raw, NotificationType.GENERATION_COMPLETED);
    }

    @KafkaListener(topics = "generation.failed", groupId = "notification-service")
    public void onGenerationFailed(String raw) {
        handle(raw, NotificationType.GENERATION_FAILED);
    }

    @KafkaListener(topics = "generation.cancelled", groupId = "notification-service")
    public void onGenerationCancelled(String raw) {
        handle(raw, NotificationType.GENERATION_CANCELLED);
    }

    @KafkaListener(topics = "user.deleted", groupId = "notification-service")
    public void onUserDeleted(String raw) {
        handle(raw, NotificationType.USER_DELETED);
    }

    void handle(String raw, NotificationType type) {
        try {
            Map<String, Object> payload = objectMapper.readValue(raw, new TypeReference<>() {
            });
            UUID userId = extractUuid(payload.get("userId"));
            if (userId == null) {
                log.warn("Skipping {} event without userId: {}", type.wireName(), raw);
                return;
            }
            UUID generationId = extractUuid(payload.get("generationId"));
            publisher.publish(new NotificationMessage(type, generationId, userId, payload));
        } catch (Exception e) {
            log.warn("Failed to handle {} event: {}", type.wireName(), e.getMessage());
        }
    }

    private UUID extractUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}