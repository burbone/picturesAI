package com.picturesAI.generation_service.metrics;

import com.picturesAI.generation_service.worker.Dispatcher;
import com.picturesAI.generation_service.worker.DispatcherProperties;
import com.picturesAI.generation_service.worker.TaskWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DispatcherMetricsTest {

    @Test
    void registersFourGauges() {
        DispatcherProperties props = new DispatcherProperties(3, 2, 1, 10, 10, 50);
        TaskWorker noop = task -> {
        };
        Dispatcher dispatcher = new Dispatcher(props, noop);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new DispatcherMetrics(dispatcher, registry);

        assertThat(registry.find("generation_queue_depth").tag("tier", "PRO").gauge()).isNotNull();
        assertThat(registry.find("generation_queue_depth").tag("tier", "FREE").gauge()).isNotNull();
        assertThat(registry.find("generation_active_slots").tag("tier", "PRO").gauge()).isNotNull();
        assertThat(registry.find("generation_active_slots").tag("tier", "FREE").gauge()).isNotNull();

        dispatcher.shutdown();
    }

    @Test
    void gaugesReflectDispatcherState() {
        DispatcherProperties props = new DispatcherProperties(3, 2, 1, 10, 10, 50);
        TaskWorker noop = task -> {
        };
        Dispatcher dispatcher = new Dispatcher(props, noop);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new DispatcherMetrics(dispatcher, registry);

        assertThat(registry.find("generation_queue_depth").tag("tier", "PRO").gauge().value())
                .isZero();
        assertThat(registry.find("generation_active_slots").tag("tier", "FREE").gauge().value())
                .isZero();

        dispatcher.shutdown();
    }
}