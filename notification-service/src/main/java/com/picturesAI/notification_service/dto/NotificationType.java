package com.picturesAI.notification_service.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum NotificationType {
    GENERATION_QUEUED("generation.queued"),
    GENERATION_PROCESSING("generation.processing"),
    GENERATION_COMPLETED("generation.completed"),
    GENERATION_FAILED("generation.failed"),
    GENERATION_CANCELLED("generation.cancelled"),
    USER_DELETED("user.deleted");

    private final String wireName;

    NotificationType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static NotificationType fromWire(String value) {
        return Arrays.stream(values())
                .filter(t -> t.wireName.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown notification type: " + value));
    }
}