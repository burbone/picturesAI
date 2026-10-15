package com.picturesAI.generation_service.worker.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.domain.Tier;
import com.picturesAI.generation_service.worker.GenerationTask;
import com.picturesAI.generation_service.worker.TaskSink;
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

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

public abstract class AbstractGenerationConsumer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AbstractGenerationConsumer.class);
    private static final String SHUTDOWN_JOIN_MS = "60000";

    private final Tier tier;
    private final String topic;
    private final KafkaConsumer<String, String> consumer;
    private final TaskSink sink;
    private final OffsetTrackerRegistry registry;
    private final ObjectMapper mapper;
    private final ConsumerProperties props;

    private final Deque<ConsumerRecord<String, String>> retained = new ArrayDeque<>();
    private final Object stateLock = new Object();

    private volatile boolean running;
    private Thread loopThread;

    protected AbstractGenerationConsumer(Tier tier,
                                         String topic,
                                         KafkaConsumer<String, String> consumer,
                                         TaskSink sink,
                                         OffsetTrackerRegistry registry,
                                         ObjectMapper mapper,
                                         ConsumerProperties props) {
        this.tier = tier;
        this.topic = topic;
        this.consumer = consumer;
        this.sink = sink;
        this.registry = registry;
        this.mapper = mapper;
        this.props = props;
    }

    public final void start() {
        synchronized (stateLock) {
            if (running) {
                return;
            }
            running = true;
        }
        consumer.subscribe(List.of(topic), new RebalanceListener());
        loopThread = new Thread(this, "consumer-" + tier.name().toLowerCase());
        loopThread.setDaemon(false);
        loopThread.start();
        log.info("Consumer {} started on topic {}", tier, topic);
    }

    @PreDestroy
    public final void shutdown() {
        synchronized (stateLock) {
            if (!running) {
                return;
            }
            running = false;
        }
        consumer.wakeup();
        if (loopThread != null) {
            try {
                loopThread.join(Long.parseLong(SHUTDOWN_JOIN_MS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        commitSafeSync();
        try {
            consumer.close(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("Consumer {} close failed: {}", tier, e.toString());
        }
        log.info("Consumer {} stopped", tier);
    }

    @Override
    public final void run() {
        try {
            while (running) {
                drainRetained();
                if (!retained.isEmpty()) {
                    applyBackpressure();
                    LockSupport.parkNanos(Duration.ofMillis(props.idleParkMs()).toNanos());
                    continue;
                }
                ConsumerRecords<String, String> records =
                        consumer.poll(Duration.ofMillis(props.pollTimeoutMs()));
                if (!records.isEmpty()) {
                    List<ConsumerRecord<String, String>> batch = new ArrayList<>();
                    records.forEach(batch::add);
                    handleBatch(batch);
                }
                applyBackpressure();
                commitSafeAsync();
            }
        } catch (WakeupException e) {
            if (running) {
                log.warn("Consumer {} wakeup without shutdown", tier, e);
            }
        } catch (Exception e) {
            log.warn("Consumer {} loop error: {}", tier, e.toString());
        }
    }

    private void handleBatch(List<ConsumerRecord<String, String>> batch) {
        for (int i = 0; i < batch.size(); i++) {
            ConsumerRecord<String, String> r = batch.get(i);
            if (!trySubmit(r)) {
                for (int j = i; j < batch.size(); j++) {
                    retained.add(batch.get(j));
                }
                return;
            }
        }
    }

    private void drainRetained() {
        while (!retained.isEmpty() && running) {
            if (!trySubmit(retained.peek())) {
                return;
            }
            retained.poll();
        }
    }

    private boolean trySubmit(ConsumerRecord<String, String> record) {
        TopicPartition tp = new TopicPartition(record.topic(), record.partition());
        PartitionOffsetTracker tracker = registry.get(tp);
        tracker.register(record.offset());

        GenerationTask task;
        try {
            task = parseTask(record);
        } catch (Exception e) {
            log.warn("Consumer {} skipping malformed record at {}@{}: {}",
                    tier, record.partition(), record.offset(), e.toString());
            tracker.markCompleted(record.offset());
            return true;
        }

        Runnable onComplete = () -> tracker.markCompleted(record.offset());
        boolean accepted = tier == Tier.PRO
                ? sink.offerPro(task, onComplete)
                : sink.offerFree(task, onComplete);
        if (!accepted) {
            log.debug("Consumer {} queue full, retaining offset {}@{}",
                    tier, record.partition(), record.offset());
        }
        return accepted;
    }

    private GenerationTask parseTask(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = mapper.readTree(record.value());
            String id = node.path("generationId").asText(null);
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("missing generationId");
            }
            return new GenerationTask(UUID.fromString(id), tier);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to parse record at offset " + record.offset(), e);
        }
    }

    private void applyBackpressure() {
        Set<TopicPartition> assignment = consumer.assignment();
        if (assignment.isEmpty()) {
            return;
        }
        if (sink.shouldPauseConsumer(tier)) {
            Set<TopicPartition> paused = consumer.paused();
            Set<TopicPartition> toPause = new HashSet<>(assignment);
            toPause.removeAll(paused);
            if (!toPause.isEmpty()) {
                consumer.pause(toPause);
                log.debug("Consumer {} paused partitions {}", tier, toPause);
            }
        } else if (sink.shouldResumeConsumer(tier)) {
            Set<TopicPartition> paused = consumer.paused();
            if (!paused.isEmpty()) {
                consumer.resume(paused);
                log.debug("Consumer {} resumed partitions {}", tier, paused);
            }
        }
    }

    private void commitSafeAsync() {
        Map<TopicPartition, OffsetAndMetadata> commits = collectSafeCommits();
        if (commits.isEmpty()) {
            return;
        }
        try {
            consumer.commitAsync(commits, (offsets, e) -> {
                if (e != null) {
                    log.warn("Consumer {} async commit failed: {}", tier, e.toString());
                }
            });
        } catch (Exception e) {
            log.warn("Consumer {} async commit threw: {}", tier, e.toString());
        }
    }

    private void commitSafeSync() {
        Map<TopicPartition, OffsetAndMetadata> commits = collectSafeCommits();
        if (commits.isEmpty()) {
            return;
        }
        try {
            consumer.commitSync(commits, Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("Consumer {} sync commit failed: {}", tier, e.toString());
        }
    }

    private Map<TopicPartition, OffsetAndMetadata> collectSafeCommits() {
        Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>();
        for (TopicPartition tp : consumer.assignment()) {
            PartitionOffsetTracker tracker = registry.get(tp);
            long safe = tracker.safeCommitOffset();
            if (safe > 0) {
                commits.put(tp, new OffsetAndMetadata(safe));
            }
        }
        return commits;
    }

    private final class RebalanceListener implements org.apache.kafka.clients.consumer.ConsumerRebalanceListener {

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            log.info("Consumer {} revoking partitions {}", tier, partitions);
            Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>();
            for (TopicPartition tp : partitions) {
                PartitionOffsetTracker tracker = registry.get(tp);
                long safe = tracker.safeCommitOffset();
                if (safe > 0) {
                    commits.put(tp, new OffsetAndMetadata(safe));
                }
            }
            if (!commits.isEmpty()) {
                try {
                    consumer.commitSync(commits, Duration.ofSeconds(5));
                } catch (Exception e) {
                    log.warn("Consumer {} revoke commit failed: {}", tier, e.toString());
                }
            }
            for (TopicPartition tp : partitions) {
                registry.reset(tp);
            }
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            log.info("Consumer {} assigned partitions {}", tier, partitions);
            for (TopicPartition tp : partitions) {
                registry.reset(tp);
            }
        }
    }
}