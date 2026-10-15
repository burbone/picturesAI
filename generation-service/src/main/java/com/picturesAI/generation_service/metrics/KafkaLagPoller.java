package com.picturesAI.generation_service.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Profile("!test")
public class KafkaLagPoller {

    private static final Logger log = LoggerFactory.getLogger(KafkaLagPoller.class);

    private final AdminClient admin;
    private final List<String> groups;
    private final ConcurrentHashMap<String, AtomicLong> lags = new ConcurrentHashMap<>();

    public KafkaLagPoller(@Value("${spring.kafka.bootstrap-servers}") String bootstrap,
                          MeterRegistry registry,
                          @Value("${generation.consumer.pro-group-id}") String proGroup,
                          @Value("${generation.consumer.free-group-id}") String freeGroup) {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrap);
        props.put("request.timeout.ms", "5000");
        props.put("default.api.timeout.ms", "5000");
        this.admin = AdminClient.create(props);
        this.groups = List.of(proGroup, freeGroup, "generation-service-user-deleted");
        for (String g : groups) {
            AtomicLong ref = lags.computeIfAbsent(g, k -> new AtomicLong(0));
            Gauge.builder("kafka_consumer_lag", ref, AtomicLong::get)
                    .tag("group", g)
                    .description("Sum of partition lag for consumer group")
                    .register(registry);
        }
    }

    @PreDestroy
    void shutdown() {
        try {
            admin.close(Duration.ofSeconds(5));
        } catch (Exception ignored) {
        }
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void poll() {
        for (String group : groups) {
            try {
                long lag = computeLag(group);
                AtomicLong ref = lags.get(group);
                if (ref != null) {
                    ref.set(lag);
                }
            } catch (Exception e) {
                log.debug("Lag poll failed for {}: {}", group, e.toString());
            }
        }
    }

    private long computeLag(String group) throws Exception {
        ListConsumerGroupOffsetsResult offsetsResult = admin.listConsumerGroupOffsets(group);
        Map<TopicPartition, OffsetAndMetadata> committed =
                offsetsResult.partitionsToOffsetAndMetadata().get();
        if (committed.isEmpty()) {
            return 0L;
        }

        Map<TopicPartition, OffsetSpec> specs = new HashMap<>();
        for (TopicPartition tp : committed.keySet()) {
            specs.put(tp, OffsetSpec.latest());
        }
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest =
                admin.listOffsets(specs).all().get();

        long total = 0L;
        for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
            ListOffsetsResult.ListOffsetsResultInfo info = latest.get(e.getKey());
            if (info == null) {
                continue;
            }
            long committedOffset = e.getValue().offset();
            long endOffset = info.offset();
            total += Math.max(0L, endOffset - committedOffset);
        }
        return total;
    }
}