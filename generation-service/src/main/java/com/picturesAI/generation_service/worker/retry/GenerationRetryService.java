package com.picturesAI.generation_service.worker.retry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.domain.Generation;
import com.picturesAI.generation_service.domain.GenerationStatus;
import com.picturesAI.generation_service.domain.OutboxEvent;
import com.picturesAI.generation_service.repository.GenerationRepository;
import com.picturesAI.generation_service.repository.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class GenerationRetryService {

    private final GenerationRepository generations;
    private final OutboxRepository outbox;
    private final RetryQueueRepository retryQueue;
    private final ObjectMapper mapper;
    private final RetryProperties props;
    private final TransactionTemplate tx;
    private final MeterRegistry meterRegistry;

    public GenerationRetryService(GenerationRepository generations,
                                  OutboxRepository outbox,
                                  RetryQueueRepository retryQueue,
                                  ObjectMapper mapper,
                                  RetryProperties props,
                                  TransactionTemplate tx,
                                  MeterRegistry meterRegistry) {
        this.generations = generations;
        this.outbox = outbox;
        this.retryQueue = retryQueue;
        this.mapper = mapper;
        this.props = props;
        this.tx = tx;
        this.meterRegistry = meterRegistry;
    }

    public RetryDecision schedule(Generation g, RetryAction action, String errorMessage) {
        int nextRetry = g.getRetryCount() + 1;
        boolean exhausted = nextRetry >= props.maxAttempts();
        if (exhausted) {
            return RetryDecision.EXHAUSTED;
        }

        final int attempt = nextRetry;
        final Instant scheduledAt = Instant.now().plusSeconds(props.delaySeconds());

        AtomicInteger updated = new AtomicInteger();
        tx.executeWithoutResult(s -> {
            int rows = generations.transitionToRetry(
                    g.getId(),
                    GenerationStatus.PROCESSING,
                    GenerationStatus.QUEUED,
                    errorMessage);
            updated.set(rows);
            if (rows > 0) {
                retryQueue.save(buildRetryEntry(g, attempt, scheduledAt));
            }
        });
        if (updated.get() == 0) {
            return RetryDecision.DLQ;
        }

        meterRegistry.counter("generation_retry_total",
                "tier", g.getTier().name(),
                "reason", reason(action)).increment();
        return RetryDecision.SCHEDULED;
    }

    public OutboxEvent buildDlqEvent(Generation g, RetryAction action, String errorMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generationId", g.getId().toString());
        if (g.getUserId() != null) {
            payload.put("userId", g.getUserId().toString());
        }
        payload.put("tier", g.getTier().name());
        payload.put("retryCount", g.getRetryCount() + 1);
        payload.put("reason", reason(action));
        payload.put("errorMessage", errorMessage);
        payload.put("movedAt", Instant.now().toString());
        return outbox(props.dlqTopic(), g.getId(), payload);
    }

    private RetryQueueEntry buildRetryEntry(Generation g, int attempt, Instant scheduledAt) {
        RetryQueueEntry e = new RetryQueueEntry();
        e.setGenerationId(g.getId());
        e.setUserId(g.getUserId());
        e.setTier(g.getTier());
        e.setRetryCount(attempt);
        e.setScheduledAt(scheduledAt);
        return e;
    }

    private OutboxEvent outbox(String type, java.util.UUID aggregateId, Map<String, Object> payload) {
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

    private String reason(RetryAction action) {
        return switch (action) {
            case RETRY_RATE_LIMIT -> "rate_limit";
            case RETRY_SERVER -> "server_error";
            case DLQ -> "non_retryable";
        };
    }
}