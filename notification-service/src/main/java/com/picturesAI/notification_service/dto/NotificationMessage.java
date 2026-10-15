package com.picturesAI.notification_service.dto;

import java.util.Map;
import java.util.UUID;

public record NotificationMessage(
        NotificationType type,
        UUID generationId,
        UUID userId,
        Map<String, Object> payload
) {
}