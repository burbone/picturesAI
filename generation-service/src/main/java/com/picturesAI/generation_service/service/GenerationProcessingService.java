package com.picturesAI.generation_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.domain.Generation;
import com.picturesAI.generation_service.domain.GenerationStatus;
import com.picturesAI.generation_service.domain.OutboxEvent;
import com.picturesAI.generation_service.provider.GenerationRequest;
import com.picturesAI.generation_service.provider.ImageProvider;
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
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class GenerationProcessingService {

    private static final Logger log = LoggerFactory.getLogger(GenerationProcessingService.class);

    private final GenerationRepository generations;
    private final OutboxRepository outbox;
    private final List<ImageProvider> providers;
    private final ImageStorage storage;
    private final QuotaClient quota;
    private final CancellationRegistry cancellation;
    private final ProviderErrorClassifier classifier;
    private final GenerationRetryService retryService;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final MeterRegistry meterRegistry;

    public GenerationProcessingService(GenerationRepository generations,
                                       OutboxRepository outbox,
                                       List<ImageProvider> providers,
                                       ImageStorage storage,
                                       QuotaClient quota,
                                       CancellationRegistry cancellation,
                                       ProviderErrorClassifier classifier,
                                       GenerationRetryService retryService,
                                       ObjectMapper mapper,
                                       TransactionTemplate tx,
                                       MeterRegistry meterRegistry) {
        this.generations = generations;
        this.outbox = outbox;
        this.providers = providers;
        this.storage = storage;
        this.quota = quota;
        this.cancellation = cancellation;
        this.classifier = classifier;
        this.retryService = retryService;
        this.mapper = mapper;
        this.tx = tx;
        this.meterRegistry = meterRegistry;
    }

    public void process(GenerationTask task) {
        Generation g = generations.findById(task.generationId()).orElse(null);
        if (g == null) {
            log.warn("Generation {} not found", task.generationId());
            return;
        }
        if (g.getStatus() != GenerationStatus.QUEUED) {
            log.debug("Generation {} already in status {}", g.getId(), g.getStatus());
            return;
        }

        if (cancellation.isMarked(g.getId())) {
            cancelGeneration(g, GenerationStatus.QUEUED);
            return;
        }

        if (!tryClaim(g.getId())) {
            log.debug("Generation {} claim lost", g.getId());
            return;
        }

        Generation refreshed = generations.findById(g.getId()).orElseThrow();
        recordWaitTime(refreshed);

        if (cancellation.isMarked(refreshed.getId())) {
            cancelGeneration(refreshed, GenerationStatus.PROCESSING);
            return;
        }

        try {
            ImageProvider provider = selectProvider(refreshed.getModel());
            ProviderResponse resp = provider.generate(new GenerationRequest(
                    refreshed.getPrompt(),
                    refreshed.getNegativePrompt(),
                    refreshed.getModel(),
                    refreshed.getSize()));

            if (cancellation.isMarked(refreshed.getId())) {
                cancelGeneration(refreshed, GenerationStatus.PROCESSING);
                return;
            }

            ImageStorage.StoredImage stored = storage.store(
                    refreshed.getUserId(), refreshed.getId(), resp.data(), resp.contentType());

            AtomicInteger updated = new AtomicInteger();
            tx.executeWithoutResult(s -> updated.set(generations.transitionToCompleted(
                    refreshed.getId(),
                    GenerationStatus.PROCESSING,
                    GenerationStatus.COMPLETED,
                    stored.imageUrl(),
                    stored.thumbnailUrl(),
                    resp.providerName(),
                    resp.providerJobId(),
                    Instant.now())));
            if (updated.get() > 0) {
                tx.executeWithoutResult(s -> outbox.save(buildCompleted(refreshed, stored, resp.providerName())));
                quota.confirm(refreshed.getId());
                cancellation.unmark(refreshed.getId());
                meterRegistry.counter("generation_processed_total",
                        "tier", refreshed.getTier().name(), "status", "completed").increment();
            } else {
                log.warn("Generation {} completed but status changed under us", refreshed.getId());
            }
        } catch (Exception e) {
            handleFailure(refreshed, e);
        }
    }

    private void recordWaitTime(Generation refreshed) {
        Instant createdAt = refreshed.getCreatedAt();
        if (createdAt == null) {
            return;
        }
        Duration wait = Duration.between(createdAt, Instant.now());
        if (wait.isNegative()) {
            wait = Duration.ZERO;
        }
        meterRegistry.timer("generation_wait_seconds",
                "tier", refreshed.getTier().name()).record(wait);
    }

    private void handleFailure(Generation g, Exception e) {
        log.warn("Generation {} failed: {}", g.getId(), e.toString());
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        RetryAction action = classifier.classify(e);

        if (action != RetryAction.DLQ) {
            RetryDecision decision = retryService.schedule(g, action, msg);
            if (decision == RetryDecision.SCHEDULED) {
                log.info("Generation {} scheduled for retry (attempt {})", g.getId(), g.getRetryCount() + 1);
                return;
            }
        }

        AtomicInteger updated = new AtomicInteger();
        tx.executeWithoutResult(s -> updated.set(generations.transitionToFailed(
                g.getId(),
                GenerationStatus.PROCESSING,
                GenerationStatus.FAILED,
                msg,
                Instant.now())));
        if (updated.get() > 0) {
            tx.executeWithoutResult(s -> {
                outbox.save(buildFailed(g, msg));
                outbox.save(retryService.buildDlqEvent(g, action, msg));
            });
        }
        quota.release(g.getId());
        cancellation.unmark(g.getId());
        meterRegistry.counter("generation_dlq_total",
                "tier", g.getTier().name()).increment();
        meterRegistry.counter("generation_processed_total",
                "tier", g.getTier().name(), "status", "failed").increment();
    }

    private boolean tryClaim(UUID id) {
        AtomicInteger updated = new AtomicInteger();
        tx.executeWithoutResult(s -> updated.set(generations.transitionToProcessing(
                id, GenerationStatus.QUEUED, GenerationStatus.PROCESSING, Instant.now())));
        return updated.get() > 0;
    }

    private void cancelGeneration(Generation g, GenerationStatus from) {
        AtomicInteger updated = new AtomicInteger();
        tx.executeWithoutResult(s -> updated.set(generations.transitionToCancelled(
                g.getId(),
                List.of(from),
                GenerationStatus.CANCELLED,
                Instant.now())));
        if (updated.get() > 0) {
            tx.executeWithoutResult(s -> outbox.save(buildCancelled(g)));
            quota.release(g.getId());
            cancellation.unmark(g.getId());
            meterRegistry.counter("generation_processed_total",
                    "tier", g.getTier().name(), "status", "cancelled").increment();
        } else {
            log.debug("Cancellation for {} lost race (status no longer {})", g.getId(), from);
        }
    }

    private ImageProvider selectProvider(String model) {
        return providers.stream()
                .filter(p -> p.supports(model))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No provider for model " + model));
    }

    private OutboxEvent buildCompleted(Generation g, ImageStorage.StoredImage stored, String providerName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generationId", g.getId().toString());
        if (g.getUserId() != null) {
            payload.put("userId", g.getUserId().toString());
        }
        payload.put("prompt", g.getPrompt());
        payload.put("originalsBucket", stored.originalsBucket());
        payload.put("originalsKey", stored.originalsKey());
        payload.put("thumbnailsBucket", stored.thumbnailsBucket());
        payload.put("thumbnailsKey", stored.thumbnailsKey());
        payload.put("imageUrl", stored.imageUrl());
        payload.put("thumbnailUrl", stored.thumbnailUrl());
        payload.put("provider", providerName);
        payload.put("completedAt", Instant.now().toString());
        return outbox("generation.completed", g.getId(), payload);
    }

    private OutboxEvent buildFailed(Generation g, String errorMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generationId", g.getId().toString());
        if (g.getUserId() != null) {
            payload.put("userId", g.getUserId().toString());
        }
        payload.put("errorMessage", errorMessage);
        payload.put("failedAt", Instant.now().toString());
        return outbox("generation.failed", g.getId(), payload);
    }

    private OutboxEvent buildCancelled(Generation g) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generationId", g.getId().toString());
        if (g.getUserId() != null) {
            payload.put("userId", g.getUserId().toString());
        }
        payload.put("cancelledAt", Instant.now().toString());
        return outbox("generation.cancelled", g.getId(), payload);
    }

    private OutboxEvent outbox(String type, UUID aggregateId, Map<String, Object> payload) {
        OutboxEvent e = new OutboxEvent();
        e.setAggregateId(aggregateId);
        e.setEventType(type);
        try {
            e.setPayload(mapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox payload", ex);
        }
        return e;
    }
}