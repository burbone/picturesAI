package com.picturesAI.tagging_service.outbox;

import com.picturesAI.tagging_service.config.OutboxProperties;
import com.picturesAI.tagging_service.domain.OutboxEvent;
import com.picturesAI.tagging_service.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties props;
    private final TransactionTemplate tx;
    private final Counter published;
    private final Counter failed;

    public OutboxPublisher(OutboxRepository outbox,
                           KafkaTemplate<String, String> kafka,
                           OutboxProperties props,
                           TransactionTemplate tx,
                           MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.props = props;
        this.tx = tx;
        this.published = Counter.builder("outbox_published_total")
                .description("Outbox events successfully published to Kafka")
                .register(meterRegistry);
        this.failed = Counter.builder("outbox_publish_failed_total")
                .description("Outbox events that failed to publish")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${tagging.outbox.fixed-delay-ms:500}")
    public void poll() {
        List<OutboxEvent> batch =
                outbox.findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, props.batchSize()));
        for (OutboxEvent event : batch) {
            try {
                publishOne(event);
                published.increment();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException e) {
                failed.increment();
                log.warn("Failed to publish outbox event {}: {}", event.getId(), e.toString());
                return;
            }
        }
    }

    private void publishOne(OutboxEvent event)
            throws InterruptedException, ExecutionException, TimeoutException {
        kafka.send(event.getEventType(), event.getAggregateId().toString(), event.getPayload())
                .get(props.sendTimeoutMs(), TimeUnit.MILLISECONDS);
        tx.executeWithoutResult(status ->
                outbox.findById(event.getId()).ifPresent(fresh -> {
                    if (fresh.getPublishedAt() == null) {
                        fresh.setPublishedAt(Instant.now());
                        outbox.save(fresh);
                    }
                }));
    }
}