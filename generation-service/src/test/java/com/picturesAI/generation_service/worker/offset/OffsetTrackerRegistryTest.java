package com.picturesAI.generation_service.worker.offset;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OffsetTrackerRegistryTest {

    private final OffsetTrackerRegistry registry = new OffsetTrackerRegistry();

    private final TopicPartition p0 = new TopicPartition("generation.requested.free", 0);
    private final TopicPartition p1 = new TopicPartition("generation.requested.free", 1);

    @Test
    void getCreatesNewTracker() {
        assertThat(registry.get(p0)).isNotNull();
    }

    @Test
    void getReturnsSameInstanceForSamePartition() {
        PartitionOffsetTracker a = registry.get(p0);
        PartitionOffsetTracker b = registry.get(p0);

        assertThat(a).isSameAs(b);
    }

    @Test
    void differentPartitionsGetDifferentTrackers() {
        PartitionOffsetTracker a = registry.get(p0);
        PartitionOffsetTracker b = registry.get(p1);

        assertThat(a).isNotSameAs(b);
    }

    @Test
    void removeDropsTracker() {
        registry.get(p0);
        assertThat(registry.remove(p0)).isNotNull();
        assertThat(registry.remove(p0)).isNull();
    }

    @Test
    void resetOnMissingTrackerIsNoop() {
        registry.reset(p0);
    }

    @Test
    void resetClearsTrackerState() {
        PartitionOffsetTracker tracker = registry.get(p0);
        tracker.register(0);
        tracker.register(1);
        tracker.markCompleted(0);
        tracker.markCompleted(1);
        assertThat(tracker.safeCommitOffset()).isEqualTo(2);

        registry.reset(p0);

        assertThat(tracker.safeCommitOffset()).isZero();
    }
}