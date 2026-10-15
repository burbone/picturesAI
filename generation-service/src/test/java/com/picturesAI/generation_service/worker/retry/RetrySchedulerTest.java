package com.picturesAI.generation_service.worker.retry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.domain.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrySchedulerTest {

    private final RetryQueueRepository retryQueue = mock(RetryQueueRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final RetryProperties props = new RetryProperties(3, 30, "generation.requested.dlq", 100);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);

    private final RetryScheduler scheduler =
            new RetryScheduler(retryQueue, kafka, mapper, props, tx);

    @BeforeEach
    void setUp() {
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> cb = inv.getArgument(0);
            cb.accept(mock(TransactionStatus.class));
            return null;
        }).when(tx).executeWithoutResult(any());
        lenient().when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    @Test
    void republishesReadyEntryAndMarksProcessed() {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        RetryQueueEntry entry = entry(generationId, userId, Tier.FREE, 1);
        when(retryQueue.findByProcessedAtIsNullAndScheduledAtBeforeOrderByScheduledAtAsc(
                any(Instant.class), any(PageRequest.class))).thenReturn(List.of(entry));

        scheduler.poll();

        ArgumentCaptor<String> bodyCap = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("generation.requested.free"), eq(userId.toString()), bodyCap.capture());
        assertThat(bodyCap.getValue()).contains(generationId.toString());
        assertThat(bodyCap.getValue()).contains("\"retryCount\":1");
        verify(retryQueue).markProcessed(eq(entry.getId()), any(Instant.class));
    }

    @Test
    void publishesToProTopicForProTier() {
        RetryQueueEntry entry = entry(UUID.randomUUID(), UUID.randomUUID(), Tier.PRO, 2);
        when(retryQueue.findByProcessedAtIsNullAndScheduledAtBeforeOrderByScheduledAtAsc(
                any(Instant.class), any(PageRequest.class))).thenReturn(List.of(entry));

        scheduler.poll();

        verify(kafka).send(eq("generation.requested.pro"), anyString(), anyString());
    }

    @Test
    void kafkaFailureLeavesEntryUnprocessed() {
        RetryQueueEntry entry = entry(UUID.randomUUID(), UUID.randomUUID(), Tier.FREE, 1);
        when(retryQueue.findByProcessedAtIsNullAndScheduledAtBeforeOrderByScheduledAtAsc(
                any(Instant.class), any(PageRequest.class))).thenReturn(List.of(entry));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        scheduler.poll();

        verify(retryQueue, never()).markProcessed(any(), any());
    }

    @Test
    void emptyBatchDoesNothing() {
        when(retryQueue.findByProcessedAtIsNullAndScheduledAtBeforeOrderByScheduledAtAsc(
                any(Instant.class), any(PageRequest.class))).thenReturn(List.of());

        scheduler.poll();

        verify(kafka, never()).send(anyString(), anyString(), anyString());
        verify(retryQueue, never()).markProcessed(any(), any());
    }

    private RetryQueueEntry entry(UUID generationId, UUID userId, Tier tier, int retryCount) {
        RetryQueueEntry e = new RetryQueueEntry();
        e.setId(UUID.randomUUID());
        e.setGenerationId(generationId);
        e.setUserId(userId);
        e.setTier(tier);
        e.setRetryCount(retryCount);
        e.setScheduledAt(Instant.now().minusSeconds(1));
        return e;
    }
}