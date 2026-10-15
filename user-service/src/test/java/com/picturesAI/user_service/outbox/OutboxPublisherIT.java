package com.picturesAI.user_service.outbox;

import com.picturesAI.user_service.AbstractKafkaTest;
import com.picturesAI.user_service.domain.OutboxEvent;
import com.picturesAI.user_service.repository.OutboxRepository;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class OutboxPublisherIT extends AbstractKafkaTest {

    private static final String TOPIC = "user.created";

    @BeforeAll
    static void createTopics() throws Exception {
        try (Admin admin = Admin.create(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)))
                    .all().get();
        }
    }

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxRepository outboxRepository;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        consumer = newConsumer();
        consumer.subscribe(List.of(TOPIC));
    }

    private KafkaConsumer<String, String> newConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    private OutboxEvent pendingEvent(UUID aggregateId, String type, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.setId(UUID.randomUUID());
        e.setAggregateId(aggregateId);
        e.setEventType(type);
        e.setPayload(payload);
        return outboxRepository.saveAndFlush(e);
    }

    private List<ConsumerRecord<String, String>> pollByKey(String key, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            records.forEach(r -> {
                if (key.equals(r.key())) {
                    collected.add(r);
                }
            });
            if (!collected.isEmpty()) {
                break;
            }
        }
        return collected;
    }

    @Test
    void publishesPendingEventToKafkaAndMarksPublished() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = pendingEvent(aggregateId, TOPIC,
                "{\"userId\":\"" + aggregateId + "\"}");

        publisher.publishPending();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            OutboxEvent refreshed = outboxRepository.findById(event.getId()).orElseThrow();
            assertThat(refreshed.getPublishedAt()).isNotNull();
        });

        List<ConsumerRecord<String, String>> records =
                pollByKey(aggregateId.toString(), Duration.ofSeconds(10));

        assertThat(records).hasSize(1);
        assertThat(records.get(0).value()).contains(aggregateId.toString());
    }

    @Test
    void secondPublishDoesNotSendDuplicate() {
        UUID aggregateId = UUID.randomUUID();
        pendingEvent(aggregateId, TOPIC, "{\"userId\":\"" + aggregateId + "\"}");

        publisher.publishPending();
        publisher.publishPending();

        List<ConsumerRecord<String, String>> records =
                pollByKey(aggregateId.toString(), Duration.ofSeconds(10));

        assertThat(records).hasSize(1);
        assertThat(outboxRepository.countByPublishedAtIsNull()).isZero();
    }
}