package com.picturesAI.generation_service.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.domain.Tier;
import com.picturesAI.generation_service.worker.GenerationTask;
import com.picturesAI.generation_service.worker.TaskSink;
import com.picturesAI.generation_service.worker.offset.OffsetTrackerRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsumerBackpressureTest {

    private static final TopicPartition TP =
            new TopicPartition("generation.requested.free", 0);

    private KafkaConsumer<String, String> consumer;
    private ConsumerFree consumerFree;
    private TogglingSink sink;

    private volatile Set<TopicPartition> pausedState = Set.of();
    private final CopyOnWriteArrayList<Collection<TopicPartition>> pauses = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Collection<TopicPartition>> resumes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        consumer = mock(KafkaConsumer.class);
        sink = new TogglingSink();
        OffsetTrackerRegistry registry = new OffsetTrackerRegistry();

        when(consumer.assignment()).thenReturn(Set.of(TP));
        when(consumer.paused()).thenAnswer(inv -> pausedState);
        when(consumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());

        doAnswer(inv -> {
            Collection<TopicPartition> arg = inv.getArgument(0);
            pauses.add(arg);
            pausedState = Set.copyOf(arg);
            return null;
        }).when(consumer).pause(any());

        doAnswer(inv -> {
            resumes.add(inv.getArgument(0));
            pausedState = Set.of();
            return null;
        }).when(consumer).resume(any());

        ConsumerProperties props = new ConsumerProperties(
                "generation.requested.pro",
                "generation.requested.free",
                "pro-group",
                "free-group",
                50L,
                10L);

        consumerFree = new ConsumerFree(consumer, sink, registry, new ObjectMapper(), props);
    }

    @AfterEach
    void tearDown() {
        consumerFree.shutdown();
    }

    @Test
    void pausesWhenSinkSignalsFull() {
        sink.pause.set(true);
        sink.resume.set(false);

        consumerFree.start();

        await().atMost(Duration.ofSeconds(5)).until(() -> !pauses.isEmpty());
        assertThat(pauses).isNotEmpty();
        assertThat(pauses.get(0)).containsExactly(TP);
    }

    @Test
    void doesNotPauseWhenSinkSignalsRoom() throws Exception {
        sink.pause.set(false);

        consumerFree.start();

        Thread.sleep(500);
        assertThat(pauses).isEmpty();
    }

    @Test
    void resumesAfterSinkDrains() {
        sink.pause.set(true);
        sink.resume.set(false);

        consumerFree.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> !pauses.isEmpty());

        sink.pause.set(false);
        sink.resume.set(true);

        await().atMost(Duration.ofSeconds(5)).until(() -> !resumes.isEmpty());
        assertThat(resumes).isNotEmpty();
        assertThat(resumes.get(0)).containsExactly(TP);
    }

    private static final class TogglingSink implements TaskSink {

        final AtomicBoolean pause = new AtomicBoolean(false);
        final AtomicBoolean resume = new AtomicBoolean(false);

        @Override
        public boolean offerPro(GenerationTask task, Runnable onComplete) {
            return true;
        }

        @Override
        public boolean offerFree(GenerationTask task, Runnable onComplete) {
            return true;
        }

        @Override
        public boolean shouldPauseConsumer(Tier tier) {
            return pause.get();
        }

        @Override
        public boolean shouldResumeConsumer(Tier tier) {
            return resume.get();
        }
    }
}