package com.picturesAI.generation_service.worker.userdeleted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.service.UserDeletedService;
import com.picturesAI.generation_service.worker.offset.OffsetTrackerRegistry;
import com.picturesAI.generation_service.worker.offset.PartitionOffsetTracker;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("!test")
public class UserDeletedConsumer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(UserDeletedConsumer.class);
    private static final long SHUTDOWN_JOIN_MS = 60_000L;

    private final KafkaConsumer<String, String> consumer;
    private final OffsetTrackerRegistry registry;
    private final UserDeletedService service;
    private final ObjectMapper mapper;

    private final Object stateLock = new Object();
    private volatile boolean running;
    private Thread loopThread;

    public UserDeletedConsumer(@Qualifier("userDeletedKafkaConsumer") KafkaConsumer<String, String> consumer,
                               OffsetTrackerRegistry registry,
                               UserDeletedService service,
                               ObjectMapper mapper) {
        this.consumer = consumer;
        this.registry = registry;
        this.service = service;
        this.mapper = mapper;
    }

    public void start() {
        synchronized (stateLock) {
            if (running) {
                return;
            }
            running = true;
        }
        consumer.subscribe(List.of("user.deleted"), new RebalanceListener());
        loopThread = new Thread(this, "consumer-user-deleted");
        loopThread.setDaemon(false);
        loopThread.start();
        log.info("UserDeletedConsumer started on topic user.deleted");
    }

    @PreDestroy
    public void shutdown() {
        synchronized (stateLock) {
            if (!running) {
                return;
            }
            running = false;
        }
        consumer.wakeup();
        if (loopThread != null) {
            try {
                loopThread.join(SHUTDOWN_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        commitSafe(true);
        try {
            consumer.close(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("UserDeletedConsumer close failed: {}", e.toString());
        }
        log.info("UserDeletedConsumer stopped");
    }

    @Override
    public void run() {
        try {
            while (running) {
                ConsumerRecords<String, String> records =
                        consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    if (!running) {
                        break;
                    }
                    handle(r);
                }
                commitSafe(false);
            }
        } catch (WakeupException e) {
            if (running) {
                log.warn("UserDeletedConsumer wakeup without shutdown", e);
            }
        } catch (Exception e) {
            log.warn("UserDeletedConsumer loop error: {}", e.toString());
        }
    }

    private void handle(ConsumerRecord<String, String> record) {
        TopicPartition tp = new TopicPartition(record.topic(), record.partition());
        PartitionOffsetTracker tracker = registry.get(tp);
        tracker.register(record.offset());

        try {
            JsonNode node = mapper.readTree(record.value());
            String userIdRaw = node.path("userId").asText(null);
            if (userIdRaw == null || userIdRaw.isBlank()) {
                log.warn("user.deleted missing userId at offset {} — skipping", record.offset());
                tracker.markCompleted(record.offset());
                return;
            }
            UUID userId = UUID.fromString(userIdRaw);
            service.anonymize(userId);
            tracker.markCompleted(record.offset());
        } catch (Exception e) {
            log.warn("user.deleted processing failed at offset {}: {}", record.offset(), e.toString());
            tracker.markCompleted(record.offset());
        }
    }

    private void commitSafe(boolean sync) {
        Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>();
        for (TopicPartition tp : consumer.assignment()) {
            long safe = registry.get(tp).safeCommitOffset();
            if (safe > 0) {
                commits.put(tp, new OffsetAndMetadata(safe));
            }
        }
        if (commits.isEmpty()) {
            return;
        }
        try {
            if (sync) {
                consumer.commitSync(commits, Duration.ofSeconds(5));
            } else {
                consumer.commitAsync(commits, (offsets, e) -> {
                    if (e != null) {
                        log.warn("UserDeletedConsumer async commit failed: {}", e.toString());
                    }
                });
            }
        } catch (Exception e) {
            log.warn("UserDeletedConsumer commit failed: {}", e.toString());
        }
    }

    private final class RebalanceListener
            implements org.apache.kafka.clients.consumer.ConsumerRebalanceListener {

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>();
            for (TopicPartition tp : partitions) {
                long safe = registry.get(tp).safeCommitOffset();
                if (safe > 0) {
                    commits.put(tp, new OffsetAndMetadata(safe));
                }
            }
            if (!commits.isEmpty()) {
                try {
                    consumer.commitSync(commits, Duration.ofSeconds(5));
                } catch (Exception e) {
                    log.warn("UserDeletedConsumer revoke commit failed: {}", e.toString());
                }
            }
            for (TopicPartition tp : partitions) {
                registry.reset(tp);
            }
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            for (TopicPartition tp : partitions) {
                registry.reset(tp);
            }
        }
    }
}