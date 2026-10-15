package com.picturesAI.notification_service.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.notification_service.config.NatsProperties;
import com.picturesAI.notification_service.dto.NotificationMessage;
import com.picturesAI.notification_service.metrics.NotificationMetrics;
import io.nats.client.Connection;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class NatsPublisher {

    private final Connection connection;
    private final String subject;
    private final ObjectMapper objectMapper;
    private final NotificationMetrics metrics;

    public NatsPublisher(Connection connection, NatsProperties props, ObjectMapper objectMapper, NotificationMetrics metrics) {
        this.connection = connection;
        this.subject = props.subject();
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public void publish(NotificationMessage message) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(message);
            connection.publish(subject, body);
            metrics.natsPublished(message.type());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to publish notification message", e);
        }
    }
}