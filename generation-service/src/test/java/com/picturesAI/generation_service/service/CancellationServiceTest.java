package com.picturesAI.generation_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.domain.Generation;
import com.picturesAI.generation_service.domain.GenerationStatus;
import com.picturesAI.generation_service.domain.OutboxEvent;
import com.picturesAI.generation_service.domain.Tier;
import com.picturesAI.generation_service.quota.QuotaClient;
import com.picturesAI.generation_service.repository.GenerationRepository;
import com.picturesAI.generation_service.repository.OutboxRepository;
import com.picturesAI.generation_service.worker.cancel.CancellationRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CancellationServiceTest {

    private final GenerationRepository generations = mock(GenerationRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final QuotaClient quota = mock(QuotaClient.class);
    private final CancellationRegistry cancellation = mock(CancellationRegistry.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final CancellationService service = new CancellationService(
            generations, outbox, quota, cancellation, mapper, tx, registry);

    @BeforeEach
    void setUp() {
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> cb = inv.getArgument(0);
            cb.accept(mock(TransactionStatus.class));
            return null;
        }).when(tx).executeWithoutResult(any());
    }

    @Test
    void notFound() {
        UUID id = UUID.randomUUID();
        when(generations.findById(id)).thenReturn(Optional.empty());

        CancelResult result = service.cancel(UUID.randomUUID(), id);

        assertThat(result).isInstanceOf(CancelResult.NotFound.class);
        verify(cancellation, never()).mark(any());
    }

    @Test
    void forbiddenWhenNotOwner() {
        Generation g = queued();
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));

        CancelResult result = service.cancel(UUID.randomUUID(), g.getId());

        assertThat(result).isInstanceOf(CancelResult.Forbidden.class);
        verify(cancellation, never()).mark(any());
    }

    @Test
    void alreadyTerminalCompleted() {
        Generation g = queued();
        g.setStatus(GenerationStatus.COMPLETED);
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));

        CancelResult result = service.cancel(g.getUserId(), g.getId());

        assertThat(result).isInstanceOf(CancelResult.AlreadyTerminal.class);
        verify(cancellation, never()).mark(any());
    }

    @Test
    void alreadyTerminalCancelled() {
        Generation g = queued();
        g.setStatus(GenerationStatus.CANCELLED);
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));

        CancelResult result = service.cancel(g.getUserId(), g.getId());

        assertThat(result).isInstanceOf(CancelResult.AlreadyTerminal.class);
    }

    @Test
    void queuedTransitionSucceedsImmediately() {
        Generation g = queued();
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));
        when(generations.transitionToCancelled(eq(g.getId()),
                eq(List.of(GenerationStatus.QUEUED)),
                eq(GenerationStatus.CANCELLED), any())).thenReturn(1);

        CancelResult result = service.cancel(g.getUserId(), g.getId());

        assertThat(result).isInstanceOf(CancelResult.Cancelled.class);
        verify(cancellation).mark(g.getId());
        verify(outbox).save(any(OutboxEvent.class));
        verify(quota).release(g.getId());
        verify(cancellation).unmark(g.getId());
        assertThat(registry.counter("generation_processed_total",
                "tier", "FREE", "status", "cancelled").count()).isEqualTo(1.0);
    }

    @Test
    void queuedRaceLostReturnsAccepted() {
        Generation g = queued();
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));
        when(generations.transitionToCancelled(eq(g.getId()),
                eq(List.of(GenerationStatus.QUEUED)),
                eq(GenerationStatus.CANCELLED), any())).thenReturn(0);

        CancelResult result = service.cancel(g.getUserId(), g.getId());

        assertThat(result).isInstanceOf(CancelResult.Accepted.class);
        verify(cancellation).mark(g.getId());
        verify(cancellation, never()).unmark(any());
        verify(quota, never()).release(any());
    }

    @Test
    void processingReturnsAcceptedWithoutImmediateTransition() {
        Generation g = queued();
        g.setStatus(GenerationStatus.PROCESSING);
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));

        CancelResult result = service.cancel(g.getUserId(), g.getId());

        assertThat(result).isInstanceOf(CancelResult.Accepted.class);
        verify(cancellation).mark(g.getId());
        verify(generations, never()).transitionToCancelled(any(), any(), any(), any());
    }

    private Generation queued() {
        Generation g = new Generation();
        g.setId(UUID.randomUUID());
        g.setUserId(UUID.randomUUID());
        g.setPrompt("p");
        g.setModel("m");
        g.setSize("512x512");
        g.setTier(Tier.FREE);
        g.setStatus(GenerationStatus.QUEUED);
        return g;
    }
}