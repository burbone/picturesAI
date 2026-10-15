package com.picturesAI.tagging_service.metrics;

import com.picturesAI.tagging_service.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@Profile("!test")
public class OutboxMetrics {

    private final OutboxRepository outbox;
    private final AtomicLong pending = new AtomicLong();

    public OutboxMetrics(OutboxRepository outbox, MeterRegistry registry) {
        this.outbox = outbox;
        Gauge.builder("outbox_pending_total", pending, AtomicLong::get)
                .description("Unpublished outbox events")
                .register(registry);
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void refresh() {
        long count = outbox.countByPublishedAtIsNull();
        pending.set(count);
    }
}