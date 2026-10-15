package com.picturesAI.notification_service.metrics;

import com.picturesAI.notification_service.dto.NotificationType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class NotificationMetrics {

    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void bindSessionGauges(Supplier<Number> generationCount, Supplier<Number> userCount) {
        Gauge.builder("ws_active_sessions", generationCount)
                .tag("type", "generation")
                .description("Active WebSocket sessions")
                .register(registry);
        Gauge.builder("ws_active_sessions", userCount)
                .tag("type", "user")
                .description("Active WebSocket sessions")
                .register(registry);
    }

    public void sessionOpened(String type) {
        Counter.builder("ws_sessions_opened_total")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    public void sessionClosed(String type, String reason) {
        Counter.builder("ws_sessions_closed_total")
                .tag("type", type)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void natsPublished(NotificationType type) {
        Counter.builder("nats_published_total")
                .tag("type", type.wireName())
                .register(registry)
                .increment();
    }

    public void natsReceived(NotificationType type) {
        Counter.builder("nats_received_total")
                .tag("type", type.wireName())
                .register(registry)
                .increment();
    }

    public void notificationSent(NotificationType type, boolean success) {
        Counter.builder("notification_send_total")
                .tag("type", type.wireName())
                .tag("result", success ? "ok" : "error")
                .register(registry)
                .increment();
    }
}