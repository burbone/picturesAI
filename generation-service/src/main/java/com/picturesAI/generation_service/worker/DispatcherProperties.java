package com.picturesAI.generation_service.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "generation.dispatcher")
public record DispatcherProperties(
        int totalSlots,
        int slotsProReserved,
        int slotsFreeReserved,
        int premiumQueueCapacity,
        int freeQueueCapacity,
        long idlePollMs) {

    public DispatcherProperties {
        if (totalSlots <= 0) {
            throw new IllegalArgumentException("totalSlots must be positive");
        }
        if (slotsProReserved < 0 || slotsFreeReserved < 0) {
            throw new IllegalArgumentException("reserved slots must be non-negative");
        }
        if (slotsProReserved + slotsFreeReserved > totalSlots) {
            throw new IllegalArgumentException(
                    "slotsProReserved + slotsFreeReserved must be <= totalSlots");
        }
        if (premiumQueueCapacity <= 0 || freeQueueCapacity <= 0) {
            throw new IllegalArgumentException("queue capacities must be positive");
        }
        if (idlePollMs <= 0) {
            idlePollMs = 50;
        }
    }
}