package com.picturesAI.generation_service.worker.retry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final RetryQueueRepository retryQueue;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final RetryProperties props;
    private final TransactionTemplate tx;

    public RetryScheduler(RetryQueueRepository retryQueue,
                          KafkaTemplate<String, String> kafka,
                          ObjectMapper mapper,
                          RetryProperties props,
                          TransactionTemplate tx) {
        this.retryQueue = retryQueue;
        this.kafka = kafka;
        this.mapper = mapper;
        this.props = props;
        this.tx = tx;
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        List<RetryQueueEntry> batch = retryQueue
                .findByProcessedAtIsNullAndScheduledAtBeforeOrderByScheduledAtAsc(
                        Instant.now(), PageRequest.of(0, props.schedulerBatchSize()));
        if (batch.isEmpty()) {
            return;
        }
        log.info("RetryScheduler picked {} entries", batch.size());
        for (RetryQueueEntry entry : batch) {
            try {
                republish(entry);
                tx.executeWithoutResult(s ->
                        retryQueue.markProcessed(entry.getId(), Instant.now()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("RetryScheduler republish failed for entry {}: {}",
                        entry.getId(), e.toString());
            }
        }
    }

    private void republish(RetryQueueEntry entry) throws Exception {
        String topic = entry.getTier() == com.picturesAI.generation_service.domain.Tier.PRO
                ? "generation.requested.pro"
                : "generation.requested.free";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generationId", entry.getGenerationId().toString());
        if (entry.getUserId() != null) {
            payload.put("userId", entry.getUserId().toString());
        }
        payload.put("tier", entry.getTier().name());
        payload.put("retryCount", entry.getRetryCount());
        payload.put("scheduledAt", entry.getScheduledAt().toString());

        String key = entry.getUserId() != null
                ? entry.getUserId().toString()
                : entry.getGenerationId().toString();

        kafka.send(topic, key, mapper.writeValueAsString(payload))
                .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        log.info("RetryScheduler republished jobId={} to {}", entry.getGenerationId(), topic);
    }
}