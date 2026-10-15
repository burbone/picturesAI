package com.picturesAI.generation_service.worker.retry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.domain.Generation;
import com.picturesAI.generation_service.domain.GenerationStatus;
import com.picturesAI.generation_service.domain.OutboxEvent;
import com.picturesAI.generation_service.domain.Tier;
import com.picturesAI.generation_service.repository.GenerationRepository;
import com.picturesAI.generation_service.repository.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
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
import org.mockito.ArgumentCaptor;

class GenerationRetryServiceTest {

    private final GenerationRepository generations = mock(GenerationRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final RetryQueueRepository retryQueue = mock(RetryQueueRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final RetryProperties props = new RetryProperties(
            3, 30, "generation.requested.dlq", 100);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);

    private final GenerationRetryService service =
            new GenerationRetryService(generations, outbox, retryQueue, mapper, props, tx, registry);

    @BeforeEach
    void setUp() {
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> cb = inv.getArgument(0);
            cb.accept(mock(TransactionStatus.class));
            return null;
        }).when(tx).executeWithoutResult(any());
    }

    @Test
    void firstFailureSchedulesRetry() {
        Generation g = queuedWithRetryCount(0);
        when(generations.transitionToRetry(eq(g.getId()), eq(GenerationStatus.PROCESSING),
                eq(GenerationStatus.QUEUED), anyString())).thenReturn(1);

        RetryDecision decision = service.schedule(g, RetryAction.RETRY_RATE_LIMIT, "429");

        assertThat(decision).isEqualTo(RetryDecision.SCHEDULED);

        ArgumentCaptor<RetryQueueEntry> captor = ArgumentCaptor.forClass(RetryQueueEntry.class);
        verify(retryQueue).save(captor.capture());
        RetryQueueEntry saved = captor.getValue();
        assertThat(saved.getGenerationId()).isEqualTo(g.getId());
        assertThat(saved.getUserId()).isEqualTo(g.getUserId());
        assertThat(saved.getTier()).isEqualTo(Tier.FREE);
        assertThat(saved.getRetryCount()).isEqualTo(1);
        assertThat(saved.getScheduledAt()).isAfter(Instant.now().plusSeconds(25));
        assertThat(saved.getProcessedAt()).isNull();

        verify(outbox, never()).save(any(OutboxEvent.class));
        assertThat(registry.counter("generation_retry_total",
                "tier", "FREE", "reason", "rate_limit").count()).isEqualTo(1.0);
    }

    @Test
    void secondFailureSchedulesRetry() {
        Generation g = queuedWithRetryCount(1);
        when(generations.transitionToRetry(any(), any(), any(), anyString())).thenReturn(1);

        RetryDecision decision = service.schedule(g, RetryAction.RETRY_SERVER, "500");

        assertThat(decision).isEqualTo(RetryDecision.SCHEDULED);
        verify(retryQueue).save(any(RetryQueueEntry.class));
    }

    @Test
    void thirdFailureExhausts() {
        Generation g = queuedWithRetryCount(2);

        RetryDecision decision = service.schedule(g, RetryAction.RETRY_SERVER, "500");

        assertThat(decision).isEqualTo(RetryDecision.EXHAUSTED);
        verify(retryQueue, never()).save(any(RetryQueueEntry.class));
    }

    @Test
    void returnsDlqWhenTransitionLosesRace() {
        Generation g = queuedWithRetryCount(0);
        when(generations.transitionToRetry(any(), any(), any(), anyString())).thenReturn(0);

        RetryDecision decision = service.schedule(g, RetryAction.RETRY_RATE_LIMIT, "429");

        assertThat(decision).isEqualTo(RetryDecision.DLQ);
        verify(retryQueue, never()).save(any(RetryQueueEntry.class));
    }

    @Test
    void dlqEventCarriesTierAndReason() {
        Generation g = queuedWithRetryCount(2);
        OutboxEvent event = service.buildDlqEvent(g, RetryAction.DLQ, "400 bad request");

        assertThat(event.getEventType()).isEqualTo("generation.requested.dlq");
        assertThat(event.getPayload()).contains("\"tier\":\"FREE\"");
        assertThat(event.getPayload()).contains("\"reason\":\"non_retryable\"");
        assertThat(event.getPayload()).contains("400 bad request");
    }

    private Generation queuedWithRetryCount(int count) {
        Generation g = new Generation();
        g.setId(UUID.randomUUID());
        g.setUserId(UUID.randomUUID());
        g.setPrompt("p");
        g.setModel("m");
        g.setSize("512x512");
        g.setTier(Tier.FREE);
        g.setStatus(GenerationStatus.PROCESSING);
        g.setRetryCount(count);
        return g;
    }
}