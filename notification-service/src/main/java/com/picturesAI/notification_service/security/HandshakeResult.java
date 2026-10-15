package com.picturesAI.notification_service.security;

import java.util.Set;
import java.util.UUID;

public record HandshakeResult(
        SubscriptionType subscriptionType,
        UUID userId,
        String username,
        Set<String> roles,
        UUID generationId,
        AccessError error
) {

    public enum SubscriptionType { USER, GENERATION }

    public enum AccessError { UNAUTHORIZED, NOT_FOUND, FORBIDDEN, INTERNAL_ERROR }

    public static HandshakeResult unauthorized() {
        return new HandshakeResult(null, null, null, Set.of(), null, AccessError.UNAUTHORIZED);
    }

    public static HandshakeResult user(UUID userId, String username, Set<String> roles) {
        return new HandshakeResult(SubscriptionType.USER, userId, username, roles, null, null);
    }

    public static HandshakeResult generation(UUID userId, String username, Set<String> roles, UUID generationId) {
        return new HandshakeResult(SubscriptionType.GENERATION, userId, username, roles, generationId, null);
    }

    public boolean hasError() {
        return error != null;
    }
}