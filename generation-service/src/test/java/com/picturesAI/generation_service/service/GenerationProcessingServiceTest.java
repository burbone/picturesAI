package com.picturesAI.generation_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.domain.Generation;
import com.picturesAI.generation_service.domain.GenerationStatus;
import com.picturesAI.generation_service.domain.OutboxEvent;
import com.picturesAI.generation_service.domain.Tier;
import com.picturesAI.generation_service.provider.GenerationRequest;
import com.picturesAI.generation_service.provider.ImageProvider;
import com.picturesAI.generation_service.provider.ProviderException;
import com.picturesAI.generation_service.provider.ProviderResponse;
import com.picturesAI.generation_service.quota.QuotaClient;
import com.picturesAI.generation_service.repository.GenerationRepository;
import com.picturesAI.generation_service.repository.OutboxRepository;
import com.picturesAI.generation_service.storage.ImageStorage;
import com.picturesAI.generation_service.worker.GenerationTask;
import com.picturesAI.generation_service.worker.cancel.CancellationRegistry;
import com.picturesAI.generation_service.worker.retry.GenerationRetryService;
import com.picturesAI.generation_service.worker.retry.ProviderErrorClassifier;
import com.picturesAI.generation_service.worker.retry.RetryAction;
import com.picturesAI.generation_service.worker.retry.RetryDecision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

class GenerationProcessingServiceTest {

    private final GenerationRepository generations = mock(GenerationRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final ImageProvider provider = mock(ImageProvider.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final QuotaClient quota = mock(QuotaClient.class);
    private final CancellationRegistry cancellation = mock(CancellationRegistry.class);
    private final ProviderErrorClassifier classifier = new ProviderErrorClassifier();
    private final GenerationRetryService retryService = mock(GenerationRetryService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final GenerationProcessingService service = new GenerationProcessingService(
            generations, outbox, List.of(provider), storage, quota,
            cancellation, classifier, retryService, mapper, tx, meterRegistry);

    @BeforeEach
    void setUp() {
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> cb = inv.getArgument(0);
            cb.accept(mock(TransactionStatus.class));
            return null;
        }).when(tx).executeWithoutResult(any());

        lenient().when(provider.name()).thenReturn("mock");
        lenient().when(provider.supports(anyString())).thenReturn(true);
        lenient().when(cancellation.isMarked(any())).thenReturn(false);
    }

    @Test
    void successPathMarksCompletedPublishesOutboxConfirmsQuota() {
        Generation g = queued();
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));
        when(generations.transitionToProcessing(eq(g.getId()), eq(GenerationStatus.QUEUED),
                eq(GenerationStatus.PROCESSING), any(Instant.class))).thenReturn(1);
        when(provider.generate(any(GenerationRequest.class)))
                .thenReturn(new ProviderResponse(new byte[] { 1, 2, 3 }, "image/png", "job-1", "mock"));
        when(storage.store(eq(g.getUserId()), eq(g.getId()), any(byte[].class), anyString()))
                .thenReturn(storedImage());
        when(generations.transitionToCompleted(eq(g.getId()), eq(GenerationStatus.PROCESSING),
                eq(GenerationStatus.COMPLETED), eq("http://img"), eq("http://thumb"),
                eq("mock"), eq("job-1"), any(Instant.class))).thenReturn(1);

        service.process(new GenerationTask(g.getId(), Tier.FREE));

        verify(outbox).save(any(OutboxEvent.class));
        verify(quota).confirm(g.getId());
        verify(quota, never()).release(any());
        verify(cancellation).unmark(g.getId());
        assertThat(meterRegistry.counter("generation_processed_total",
                "tier", "FREE", "status", "completed").count()).isEqualTo(1.0);
    }

    @Test
    void completedEventPayloadContainsAllCatalogFields() {
        Generation g = queued();
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));
        when(generations.transitionToProcessing(eq(g.getId()), eq(GenerationStatus.QUEUED),
                eq(GenerationStatus.PROCESSING), any(Instant.class))).thenReturn(1);
        when(provider.generate(any(GenerationRequest.class)))
                .thenReturn(new ProviderResponse(new byte[] { 1, 2, 3 }, "image/png", "job-1", "mock"));
        when(storage.store(eq(g.getUserId()), eq(g.getId()), any(byte[].class), anyString()))
                .thenReturn(storedImage());
        when(generations.transitionToCompleted(eq(g.getId()), eq(GenerationStatus.PROCESSING),
                eq(GenerationStatus.COMPLETED), eq("http://img"), eq("http://thumb"),
                eq("mock"), eq("job-1"), any(Instant.class))).thenReturn(1);

        service.process(new GenerationTask(g.getId(), Tier.FREE));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        OutboxEvent event = captor.getValue();

        assertThat(event.getEventType()).isEqualTo("generation.completed");
        assertThat(event.getPayload())
                .contains("\"generationId\":\"" + g.getId() + "\"")
                .contains("\"userId\":\"" + g.getUserId() + "\"")
                .contains("\"prompt\":\"a cat\"")
                .contains("\"originalsBucket\":\"originals\"")
                .contains("\"originalsKey\":\"originals-key\"")
                .contains("\"thumbnailsBucket\":\"thumbnails\"")
                .contains("\"thumbnailsKey\":\"thumbnails-key\"")
                .contains("\"imageUrl\":\"http://img\"")
                .contains("\"thumbnailUrl\":\"http://thumb\"")
                .contains("\"provider\":\"mock\"")
                .contains("\"completedAt\"");
    }

    @Test
    void cancellationMarkedBeforeClaimCancelsWithoutCallingProvider() {
        Generation g = queued();
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));
        when(cancellation.isMarked(g.getId())).thenReturn(true);
        when(generations.transitionToCancelled(eq(g.getId()),
                eq(List.of(GenerationStatus.QUEUED)),
                eq(GenerationStatus.CANCELLED), any(Instant.class))).thenReturn(1);

        service.process(new GenerationTask(g.getId(), Tier.FREE));

        verify(provider, never()).generate(any());
        verify(quota).release(g.getId());
        assertThat(meterRegistry.counter("generation_processed_total",
                "tier", "FREE", "status", "cancelled").count()).isEqualTo(1.0);
    }

    @Test
    void cancellationMarkedAfterClaimCancelsFromProcessing() {
        Generation g = queued();
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));
        when(generations.transitionToProcessing(eq(g.getId()), eq(GenerationStatus.QUEUED),
                eq(GenerationStatus.PROCESSING), any(Instant.class))).thenReturn(1);

        AtomicBoolean firstCheck = new AtomicBoolean(true);
        when(cancellation.isMarked(g.getId())).thenAnswer(inv -> !firstCheck.getAndSet(false));

        when(generations.transitionToCancelled(eq(g.getId()),
                eq(List.of(GenerationStatus.PROCESSING)),
                eq(GenerationStatus.CANCELLED), any(Instant.class))).thenReturn(1);

        service.process(new GenerationTask(g.getId(), Tier.FREE));

        verify(provider, never()).generate(any());
        verify(quota).release(g.getId());
        verify(quota, never()).confirm(any());
    }

    @Test
    void cancellationMarkedDuringProviderCallDiscardsResult() {
        Generation g = queued();
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));
        when(generations.transitionToProcessing(eq(g.getId()), eq(GenerationStatus.QUEUED),
                eq(GenerationStatus.PROCESSING), any(Instant.class))).thenReturn(1);
        when(provider.generate(any(GenerationRequest.class)))
                .thenAnswer(inv -> {
                    when(cancellation.isMarked(g.getId())).thenReturn(true);
                    return new ProviderResponse(new byte[] { 1 }, "image/png", "job-1", "mock");
                });
        when(generations.transitionToCancelled(eq(g.getId()),
                eq(List.of(GenerationStatus.PROCESSING)),
                eq(GenerationStatus.CANCELLED), any(Instant.class))).thenReturn(1);

        service.process(new GenerationTask(g.getId(), Tier.FREE));

        verify(storage, never()).store(any(), any(), any(), anyString());
        verify(quota).release(g.getId());
        verify(quota, never()).confirm(any());
        assertThat(meterRegistry.counter("generation_processed_total",
                "tier", "FREE", "status", "cancelled").count()).isEqualTo(1.0);
    }

    @Test
    void serverErrorSchedulesRetryNotDlq() {
        Generation g = queued();
        g.setRetryCount(0);
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));
        when(generations.transitionToProcessing(any(), any(), any(), any(Instant.class))).thenReturn(1);
        when(provider.generate(any(GenerationRequest.class)))
                .thenThrow(new ProviderException(500, "boom"));
        when(retryService.schedule(eq(g), eq(RetryAction.RETRY_SERVER), anyString()))
                .thenReturn(RetryDecision.SCHEDULED);

        service.process(new GenerationTask(g.getId(), Tier.FREE));

        verify(retryService).schedule(eq(g), eq(RetryAction.RETRY_SERVER), anyString());
        verify(quota, never()).release(any());
        verify(quota, never()).confirm(any());
    }

    @Test
    void rateLimitSchedulesRetry() {
        Generation g = queued();
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));
        when(generations.transitionToProcessing(any(), any(), any(), any(Instant.class))).thenReturn(1);
        when(provider.generate(any(GenerationRequest.class)))
                .thenThrow(new ProviderException(429, "slow down"));
        when(retryService.schedule(eq(g), eq(RetryAction.RETRY_RATE_LIMIT), anyString()))
                .thenReturn(RetryDecision.SCHEDULED);

        service.process(new GenerationTask(g.getId(), Tier.FREE));

        verify(retryService).schedule(eq(g), eq(RetryAction.RETRY_RATE_LIMIT), anyString());
    }

    @Test
    void nonRetryableGoesToDlq() {
        Generation g = queued();
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));
        when(generations.transitionToProcessing(any(), any(), any(), any(Instant.class))).thenReturn(1);
        when(provider.generate(any(GenerationRequest.class)))
                .thenThrow(new ProviderException(400, "bad request", false));
        when(generations.transitionToFailed(any(), any(), any(), anyString(), any(Instant.class)))
                .thenReturn(1);
        when(retryService.buildDlqEvent(any(), any(), anyString()))
                .thenReturn(dlqEvent());

        service.process(new GenerationTask(g.getId(), Tier.FREE));

        verify(retryService, never()).schedule(any(), any(), anyString());
        verify(quota).release(g.getId());
        assertThat(meterRegistry.counter("generation_dlq_total", "tier", "FREE").count()).isEqualTo(1.0);
    }

    @Test
    void exhaustedRetriesGoToDlq() {
        Generation g = queued();
        g.setRetryCount(2);
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));
        when(generations.transitionToProcessing(any(), any(), any(), any(Instant.class))).thenReturn(1);
        when(provider.generate(any(GenerationRequest.class)))
                .thenThrow(new ProviderException(500, "boom"));
        when(retryService.schedule(eq(g), eq(RetryAction.RETRY_SERVER), anyString()))
                .thenReturn(RetryDecision.EXHAUSTED);
        when(generations.transitionToFailed(any(), any(), any(), anyString(), any(Instant.class)))
                .thenReturn(1);
        when(retryService.buildDlqEvent(any(), any(), anyString()))
                .thenReturn(dlqEvent());

        service.process(new GenerationTask(g.getId(), Tier.FREE));

        verify(quota).release(g.getId());
        assertThat(meterRegistry.counter("generation_dlq_total", "tier", "FREE").count()).isEqualTo(1.0);
    }

    @Test
    void skipsWhenStatusNotQueued() {
        Generation g = queued();
        g.setStatus(GenerationStatus.PROCESSING);
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));

        service.process(new GenerationTask(g.getId(), Tier.FREE));

        verify(provider, never()).generate(any());
    }

    @Test
    void skipsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(generations.findById(id)).thenReturn(Optional.empty());

        service.process(new GenerationTask(id, Tier.FREE));

        verify(provider, never()).generate(any());
    }

    @Test
    void waitTimerRecordedWhenCreatedAtPresent() {
        Generation g = queued();
        g.setCreatedAt(Instant.now().minusSeconds(5));
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));
        when(generations.transitionToProcessing(eq(g.getId()), eq(GenerationStatus.QUEUED),
                eq(GenerationStatus.PROCESSING), any(Instant.class))).thenReturn(1);
        when(provider.generate(any(GenerationRequest.class)))
                .thenReturn(new ProviderResponse(new byte[] { 1 }, "image/png", "job-1", "mock"));
        when(storage.store(any(), any(), any(byte[].class), anyString()))
                .thenReturn(storedImage());
        when(generations.transitionToCompleted(any(), any(), any(), any(), any(), any(), any(),
                any(Instant.class))).thenReturn(1);

        service.process(new GenerationTask(g.getId(), Tier.FREE));

        assertThat(meterRegistry.timer("generation_wait_seconds", "tier", "FREE").count())
                .isEqualTo(1L);
    }

    @Test
    void waitTimerSkippedWhenCreatedAtMissing() {
        Generation g = queued();
        when(generations.findById(g.getId())).thenReturn(Optional.of(g));
        when(generations.transitionToProcessing(any(), any(), any(), any(Instant.class))).thenReturn(1);
        when(provider.generate(any(GenerationRequest.class)))
                .thenThrow(new ProviderException(500, "boom"));
        when(retryService.schedule(any(), any(), anyString())).thenReturn(RetryDecision.SCHEDULED);

        service.process(new GenerationTask(g.getId(), Tier.FREE));

        assertThat(meterRegistry.find("generation_wait_seconds").timer()).isNull();
    }

    private static ImageStorage.StoredImage storedImage() {
        return new ImageStorage.StoredImage(
                "http://img",
                "http://thumb",
                "originals",
                "originals-key",
                "thumbnails",
                "thumbnails-key");
    }

    private OutboxEvent dlqEvent() {
        OutboxEvent e = new OutboxEvent();
        e.setEventType("generation.requested.dlq");
        e.setAggregateId(UUID.randomUUID());
        e.setPayload("{}");
        return e;
    }

    private Generation queued() {
        Generation g = new Generation();
        g.setId(UUID.randomUUID());
        g.setUserId(UUID.randomUUID());
        g.setPrompt("a cat");
        g.setModel("sd-xl");
        g.setSize("512x512");
        g.setTier(Tier.FREE);
        g.setStatus(GenerationStatus.QUEUED);
        return g;
    }
}