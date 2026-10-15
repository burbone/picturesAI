package com.picturesAI.generation_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.domain.Generation;
import com.picturesAI.generation_service.domain.GenerationStatus;
import com.picturesAI.generation_service.domain.OutboxEvent;
import com.picturesAI.generation_service.quota.QuotaClient;
import com.picturesAI.generation_service.repository.GenerationRepository;
import com.picturesAI.generation_service.repository.OutboxRepository;
import com.picturesAI.generation_service.worker.cancel.CancellationRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CancellationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationService.class);

    private final GenerationRepository generations;
    private final OutboxRepository outbox;
    private final QuotaClient quota;
    private final CancellationRegistry cancellation;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final MeterRegistry meterRegistry;

    public CancellationService(GenerationRepository generations,
                               OutboxRepository outbox,
                               QuotaClient quota,
                               CancellationRegistry cancellation,
                               ObjectMapper mapper,
                               TransactionTemplate tx,
                               MeterRegistry meterRegistry) {
        this.generations = generations;
        this.outbox = outbox;
        this.quota = quota;
        this.cancellation = cancellation;
        this.mapper = mapper;
        this.tx = tx;
        this.meterRegistry = meterRegistry;
    }

    public CancelResult cancel(UUID userId, UUID generationId) {
        Generation g = generations.findById(generationId).orElse(null);
        if (g == null) {
            return new CancelResult.NotFound();
        }
        if (userId == null || !userId.equals(g.getUserId())) {
            return new CancelResult.Forbidden();
        }
        GenerationStatus status = g.getStatus();
        if (status == GenerationStatus.COMPLETED
                || status == GenerationStatus.FAILED
                || status == GenerationStatus.CANCELLED) {
            return new CancelResult.AlreadyTerminal(status);
        }

        cancellation.mark(generationId);

        if (status == GenerationStatus.QUEUED) {
            AtomicInteger updated = new AtomicInteger();
            tx.executeWithoutResult(s -> updated.set(generations.transitionToCancelled(
                    generationId,
                    List.of(GenerationStatus.QUEUED),
                    GenerationStatus.CANCELLED,
                    Instant.now())));
            if (updated.get() > 0) {
                final Generation snapshot = g;
                tx.executeWithoutResult(s -> outbox.save(buildCancelled(snapshot)));
                quota.release(generationId);
                cancellation.unmark(generationId);
                meterRegistry.counter("generation_processed_total",
                        "tier", snapshot.getTier().name(), "status", "cancelled").increment();
                return new CancelResult.Cancelled();
            }
            log.debug("Cancel race lost for {} — worker claimed QUEUED first", generationId);
        }

        return new CancelResult.Accepted();
    }

    private OutboxEvent buildCancelled(Generation g) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generationId", g.getId().toString());
        if (g.getUserId() != null) {
            payload.put("userId", g.getUserId().toString());
        }
        payload.put("cancelledAt", Instant.now().toString());
        OutboxEvent e = new OutboxEvent();
        e.setAggregateId(g.getId());
        e.setEventType("generation.cancelled");
        try {
            e.setPayload(mapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox payload", ex);
        }
        return e;
    }
}