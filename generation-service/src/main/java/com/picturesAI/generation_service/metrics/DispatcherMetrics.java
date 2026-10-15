package com.picturesAI.generation_service.metrics;

import com.picturesAI.generation_service.worker.Dispatcher;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class DispatcherMetrics {

    public DispatcherMetrics(Dispatcher dispatcher, MeterRegistry registry) {
        Gauge.builder("generation_queue_depth", dispatcher, d -> (double) d.premiumQueueDepth())
                .tag("tier", "PRO")
                .description("Pending tasks in premium queue")
                .register(registry);
        Gauge.builder("generation_queue_depth", dispatcher, d -> (double) d.freeQueueDepth())
                .tag("tier", "FREE")
                .description("Pending tasks in free queue")
                .register(registry);
        Gauge.builder("generation_active_slots", dispatcher, d -> (double) d.activePro())
                .tag("tier", "PRO")
                .description("Active provider slots for premium tier")
                .register(registry);
        Gauge.builder("generation_active_slots", dispatcher, d -> (double) d.activeFree())
                .tag("tier", "FREE")
                .description("Active provider slots for free tier")
                .register(registry);
    }
}