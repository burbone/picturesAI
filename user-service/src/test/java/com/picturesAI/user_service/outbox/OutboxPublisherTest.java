package com.picturesAI.user_service.outbox;

import com.picturesAI.user_service.domain.OutboxEvent;
import com.picturesAI.user_service.repository.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private SimpleMeterRegistry registry;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        OutboxProperties properties = new OutboxProperties();
        properties.setBatchSize(10);
        publisher = new OutboxPublisher(outboxRepository, kafkaTemplate, properties, registry);
    }

    private OutboxEvent event(UUID aggregateId, String type) {
        OutboxEvent e = new OutboxEvent();
        e.setId(UUID.randomUUID());
        e.setAggregateId(aggregateId);
        e.setEventType(type);
        e.setPayload("{\"k\":\"v\"}");
        return e;
    }

    @Test
    void publishesPendingEventAndMarksPublished() {
        OutboxEvent e = event(UUID.randomUUID(), "user.created");
        when(outboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(e));
        when(kafkaTemplate.send("user.created", e.getAggregateId().toString(), e.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(outboxRepository.countByPublishedAtIsNull()).thenReturn(0L);

        publisher.publishPending();

        assertThat(e.getPublishedAt()).isNotNull();
        verify(outboxRepository, times(1)).save(e);
        assertThat(registry.get("outbox_published_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("outbox_pending_total").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void doesNotMarkPublishedWhenKafkaFails() {
        OutboxEvent e = event(UUID.randomUUID(), "user.created");
        when(outboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(e));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("kafka down"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);
        when(outboxRepository.countByPublishedAtIsNull()).thenReturn(1L);

        publisher.publishPending();

        assertThat(e.getPublishedAt()).isNull();
        verify(outboxRepository, never()).save(any());
        assertThat(registry.get("outbox_published_total").counter().count()).isEqualTo(0.0);
        assertThat(registry.get("outbox_pending_total").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void stopsCycleOnFirstFailure() {
        OutboxEvent first = event(UUID.randomUUID(), "user.created");
        OutboxEvent second = event(UUID.randomUUID(), "user.created");
        when(outboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(first, second));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("kafka down"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);
        when(outboxRepository.countByPublishedAtIsNull()).thenReturn(2L);

        publisher.publishPending();

        assertThat(first.getPublishedAt()).isNull();
        assertThat(second.getPublishedAt()).isNull();
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void emptyBatchDoesNotQueryCount() {
        when(outboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());

        publisher.publishPending();

        verify(outboxRepository, never()).countByPublishedAtIsNull();
        assertThat(registry.get("outbox_pending_total").gauge().value()).isEqualTo(0.0);
    }
}