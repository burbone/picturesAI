package com.picturesAI.generation_service.worker.retry;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "generation.retry")
public record RetryProperties(
        int maxAttempts,
        long delaySeconds,
        String dlqTopic,
        int schedulerBatchSize) {

    public RetryProperties {
        if (maxAttempts <= 0) {
            maxAttempts = 3;
        }
        if (delaySeconds <= 0) {
            delaySeconds = 30;
        }
        if (dlqTopic == null || dlqTopic.isBlank()) {
            dlqTopic = "generation.requested.dlq";
        }
        if (schedulerBatchSize <= 0) {
            schedulerBatchSize = 100;
        }
    }
}