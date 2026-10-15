package com.picturesAI.notification_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nats")
public record NatsProperties(
        String url,
        String username,
        String password,
        String subject
) {
}