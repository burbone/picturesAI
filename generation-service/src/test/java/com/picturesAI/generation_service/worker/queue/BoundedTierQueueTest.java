package com.picturesAI.generation_service.worker.queue;

import com.picturesAI.generation_service.domain.Tier;
import com.picturesAI.generation_service.worker.GenerationTask;
import com.picturesAI.generation_service.worker.QueuedTask;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedTierQueueTest {

    private static final Runnable NOOP = () -> {
    };

    @Test
    void offersUpToCapacity() {
        BoundedTierQueue q = new BoundedTierQueue(2);

        assertThat(q.offer(task(), NOOP)).isTrue();
        assertThat(q.offer(task(), NOOP)).isTrue();
        assertThat(q.offer(task(), NOOP)).isFalse();
        assertThat(q.size()).isEqualTo(2);
        assertThat(q.isFull()).isTrue();
    }

    @Test
    void pollsFifo() {
        BoundedTierQueue q = new BoundedTierQueue(3);
        GenerationTask a = task();
        GenerationTask b = task();
        q.offer(a, NOOP);
        q.offer(b, NOOP);

        QueuedTask first = q.poll();
        QueuedTask second = q.poll();
        assertThat(first.task()).isEqualTo(a);
        assertThat(second.task()).isEqualTo(b);
        assertThat(q.poll()).isNull();
    }

    @Test
    void fillRatioReflectsOccupancy() {
        BoundedTierQueue q = new BoundedTierQueue(4);
        q.offer(task(), NOOP);
        q.offer(task(), NOOP);

        assertThat(q.fillRatio()).isEqualTo(0.5);
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new BoundedTierQueue(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GenerationTask task() {
        return new GenerationTask(UUID.randomUUID(), Tier.FREE);
    }
}