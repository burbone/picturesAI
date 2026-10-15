package com.picturesAI.tagging_service.outbox;

import com.picturesAI.tagging_service.config.OutboxProperties;
import com.picturesAI.tagging_service.domain.OutboxEvent;
import com.picturesAI.tagging_service.repository.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    private final OutboxRepository outbox = mock(OutboxRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxProperties props = new OutboxProperties(500, 100, 5000);

    private final OutboxPublisher publisher =
            new OutboxPublisher(outbox, kafka, props, tx, registry);

    @BeforeEach
    void setUp() {
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> cb = inv.getArgument(0);
            cb.accept(mock(TransactionStatus.class));
            return null;
        }).when(tx).executeWithoutResult(any());
    }

    @Test
    void successfulPublishMarksPublishedAtAndIncrementsCounter() {
        OutboxEvent e = event("tags.extracted");
        when(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc(any(PageRequest.class)))
                .thenReturn(List.of(e));
        when(outbox.findById(e.getId())).thenReturn(Optional.of(e));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.poll();

        verify(kafka).send(eq("tags.extracted"), eq(e.getAggregateId().toString()), eq(e.getPayload()));
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        assertThat(captor.getValue().getPublishedAt()).isNotNull();
        assertThat(registry.counter("outbox_published_total").count()).isEqualTo(1.0);
        assertThat(registry.counter("outbox_publish_failed_total").count()).isZero();
    }

    @Test
    void kafkaFailureBreaksLoopAndIncrementsFailedCounter() {
        OutboxEvent e1 = event("tags.extracted");
        OutboxEvent e2 = event("tags.extracted");
        when(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc(any(PageRequest.class)))
                .thenReturn(List.of(e1, e2));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        publisher.poll();

        verify(kafka).send(eq("tags.extracted"), eq(e1.getAggregateId().toString()), anyString());
        verify(kafka, never()).send(eq("tags.extracted"), eq(e2.getAggregateId().toString()), anyString());
        verify(outbox, never()).save(any(OutboxEvent.class));
        assertThat(registry.counter("outbox_publish_failed_total").count()).isEqualTo(1.0);
        assertThat(registry.counter("outbox_published_total").count()).isZero();
    }

    @Test
    void interruptStopsWithoutIncrementingFailedCounter() throws Exception {
        OutboxEvent e = event("tags.extracted");
        when(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc(any(PageRequest.class)))
                .thenReturn(List.of(e));

        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(future);

        try {
            publisher.poll();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        verify(outbox, never()).save(any(OutboxEvent.class));
        assertThat(registry.counter("outbox_publish_failed_total").count()).isZero();
        assertThat(registry.counter("outbox_published_total").count()).isZero();
    }

    @Test
    void emptyBatchProducesNoInteractions() {
        when(outbox.findByPublishedAtIsNullOrderByCreatedAtAsc(any(PageRequest.class)))
                .thenReturn(List.of());

        publisher.poll();

        verify(kafka, never()).send(anyString(), anyString(), anyString());
        verify(outbox, never()).save(any(OutboxEvent.class));
        assertThat(registry.counter("outbox_published_total").count()).isZero();
    }

    private OutboxEvent event(String type) {
        OutboxEvent e = new OutboxEvent();
        e.setId(UUID.randomUUID());
        e.setAggregateId(UUID.randomUUID());
        e.setEventType(type);
        e.setPayload("{}");
        return e;
    }
}