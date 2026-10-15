package com.picturesAI.user_service.outbox;

import com.picturesAI.user_service.domain.OutboxEvent;
import com.picturesAI.user_service.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;
    private final AtomicLong pendingGauge = new AtomicLong();
    private final Counter publishedCounter;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           OutboxProperties properties,
                           MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = properties.getBatchSize();
        this.publishedCounter = meterRegistry.counter("outbox_published_total");
        meterRegistry.gauge("outbox_pending_total", pendingGauge);
    }

    @Scheduled(
            fixedDelayString = "${outbox.publisher.poll-interval-ms:500}",
            initialDelayString = "${outbox.publisher.initial-delay-ms:0}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository
                .findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, batchSize));

        if (batch.isEmpty()) {
            pendingGauge.set(0);
            return;
        }

        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getEventType(),
                                event.getAggregateId().toString(),
                                event.getPayload())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Failed to publish outbox event id={} type={}, stopping cycle",
                        event.getId(), event.getEventType(), e);
                break;
            }
            event.setPublishedAt(Instant.now());
            outboxRepository.save(event);
            publishedCounter.increment();
        }

        pendingGauge.set(outboxRepository.countByPublishedAtIsNull());
    }
}