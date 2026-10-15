package com.picturesAI.notification_service.metrics;

import com.picturesAI.notification_service.dto.NotificationType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMetricsTest {

    private SimpleMeterRegistry registry;
    private NotificationMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new NotificationMetrics(registry);
    }

    @Test
    void sessionOpenedCounters() {
        metrics.sessionOpened("generation");
        metrics.sessionOpened("generation");
        metrics.sessionOpened("user");

        assertThat(registry.get("ws_sessions_opened_total").tag("type", "generation").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("ws_sessions_opened_total").tag("type", "user").counter().count()).isEqualTo(1.0);
    }

    @Test
    void sessionClosedByReason() {
        metrics.sessionClosed("generation", "normal");
        metrics.sessionClosed("generation", "account_deleted");
        metrics.sessionClosed("user", "send_error");

        assertThat(registry.get("ws_sessions_closed_total")
                .tag("type", "generation").tag("reason", "normal").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ws_sessions_closed_total")
                .tag("type", "generation").tag("reason", "account_deleted").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ws_sessions_closed_total")
                .tag("type", "user").tag("reason", "send_error").counter().count()).isEqualTo(1.0);
    }

    @Test
    void natsCountersUseWireName() {
        metrics.natsPublished(NotificationType.GENERATION_COMPLETED);
        metrics.natsReceived(NotificationType.GENERATION_COMPLETED);
        metrics.natsReceived(NotificationType.USER_DELETED);

        assertThat(registry.get("nats_published_total")
                .tag("type", "generation.completed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("nats_received_total")
                .tag("type", "generation.completed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("nats_received_total")
                .tag("type", "user.deleted").counter().count()).isEqualTo(1.0);
    }

    @Test
    void notificationSendResult() {
        metrics.notificationSent(NotificationType.GENERATION_FAILED, true);
        metrics.notificationSent(NotificationType.GENERATION_FAILED, false);
        metrics.notificationSent(NotificationType.GENERATION_FAILED, true);

        assertThat(registry.get("notification_send_total")
                .tag("type", "generation.failed").tag("result", "ok").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("notification_send_total")
                .tag("type", "generation.failed").tag("result", "error").counter().count()).isEqualTo(1.0);
    }

    @Test
    void bindSessionGauges() {
        AtomicInteger genCount = new AtomicInteger(3);
        AtomicInteger userCount = new AtomicInteger(5);

        metrics.bindSessionGauges(genCount::get, userCount::get);

        assertThat(registry.get("ws_active_sessions").tag("type", "generation").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("ws_active_sessions").tag("type", "user").gauge().value()).isEqualTo(5.0);

        genCount.set(7);
        assertThat(registry.get("ws_active_sessions").tag("type", "generation").gauge().value()).isEqualTo(7.0);
    }
}