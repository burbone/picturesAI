package com.picturesAI.billing_service.nats.dto;

public record ReserveResponse(boolean allowed, int remaining) {
}