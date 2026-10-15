package com.picturesAI.tagging_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tagging.outbox")
public record OutboxProperties(long fixedDelayMs, int batchSize, long sendTimeoutMs) {

    public OutboxProperties {
        if (fixedDelayMs <= 0) {
            fixedDelayMs = 500;
        }
        if (batchSize <= 0) {
            batchSize = 100;
        }
        if (sendTimeoutMs <= 0) {
            sendTimeoutMs = 10_000;
        }
    }
}