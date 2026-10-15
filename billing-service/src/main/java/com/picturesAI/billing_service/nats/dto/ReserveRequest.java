package com.picturesAI.billing_service.nats.dto;

import java.util.UUID;

public record ReserveRequest(UUID generationId, UUID userId) {
}