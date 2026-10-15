package com.picturesAI.generation_service.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.domain.OutboxEvent;
import com.picturesAI.generation_service.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final String REQUESTED_PREFIX = "generation.requested.";

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Counter failed;

    public OutboxPublisher(OutboxRepository outbox,
                           KafkaTemplate<String, String> kafka,
                           ObjectMapper mapper,
                           TransactionTemplate tx,
                           MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.mapper = mapper;
        this.tx = tx;
        this.failed = Counter.builder("outbox_publish_failed_total")
                .description("Outbox events that failed to publish to Kafka")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 500)
    public void poll() {
        List<OutboxEvent> batch =
                outbox.findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, BATCH_SIZE));
        for (OutboxEvent event : batch) {
            try {
                publishOne(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException e) {
                failed.increment();
                log.warn("Failed to publish outbox event {} ({}): {}",
                        event.getId(), event.getEventType(), e.toString());
            } catch (Exception e) {
                failed.increment();
                log.warn("Unexpected failure publishing outbox event {} ({}): {}",
                        event.getId(), event.getEventType(), e.toString());
            }
        }
    }

    private void publishOne(OutboxEvent event)
            throws InterruptedException, ExecutionException, TimeoutException {
        String topic = event.getEventType();
        String key = keyFor(event);
        kafka.send(topic, key, event.getPayload())
                .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        tx.executeWithoutResult(status ->
                outbox.findById(event.getId()).ifPresent(fresh -> {
                    if (fresh.getPublishedAt() == null) {
                        fresh.setPublishedAt(Instant.now());
                        outbox.save(fresh);
                    }
                }));
    }

    private String keyFor(OutboxEvent event) {
        if (event.getEventType().startsWith(REQUESTED_PREFIX)) {
            try {
                JsonNode node = mapper.readTree(event.getPayload());
                String userId = node.path("userId").asText(null);
                if (userId != null && !userId.isBlank()) {
                    return userId;
                }
            } catch (Exception ignored) {
            }
        }
        return event.getAggregateId().toString();
    }
}