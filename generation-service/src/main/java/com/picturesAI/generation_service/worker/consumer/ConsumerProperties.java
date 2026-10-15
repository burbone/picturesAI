package com.picturesAI.generation_service.worker.consumer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "generation.consumer")
public record ConsumerProperties(
        String proTopic,
        String freeTopic,
        String proGroupId,
        String freeGroupId,
        long pollTimeoutMs,
        long idleParkMs) {

    public ConsumerProperties {
        if (proTopic == null || proTopic.isBlank()) {
            proTopic = "generation.requested.pro";
        }
        if (freeTopic == null || freeTopic.isBlank()) {
            freeTopic = "generation.requested.free";
        }
        if (proGroupId == null || proGroupId.isBlank()) {
            proGroupId = "generation-service-workers-pro";
        }
        if (freeGroupId == null || freeGroupId.isBlank()) {
            freeGroupId = "generation-service-workers-free";
        }
        if (pollTimeoutMs <= 0) {
            pollTimeoutMs = 500;
        }
        if (idleParkMs <= 0) {
            idleParkMs = 50;
        }
    }
}