package com.picturesAI.generation_service.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.domain.Tier;
import com.picturesAI.generation_service.worker.GenerationTask;
import com.picturesAI.generation_service.worker.TaskSink;
import com.picturesAI.generation_service.worker.offset.OffsetTrackerRegistry;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ConsumerIT {

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka-native:3.9.0"))
            .withEnv("KAFKA_LISTENERS",
                    "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094");

    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> proConsumer;
    private KafkaConsumer<String, String> freeConsumer;
    private ConsumerPro consumerPro;
    private ConsumerFree consumerFree;
    private RecordingSink sink;

    private final String runId = UUID.randomUUID().toString().substring(0, 8);
    private final String proTopic = "generation.requested.pro.it." + runId;
    private final String freeTopic = "generation.requested.free.it." + runId;
    private final String proGroup = "it-pro-" + UUID.randomUUID();
    private final String freeGroup = "it-free-" + UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        producer.send(new ProducerRecord<>(proTopic, "warmup", "{}")).get();
        producer.send(new ProducerRecord<>(freeTopic, "warmup", "{}")).get();

        proConsumer = new KafkaConsumer<>(consumerConfig(proGroup));
        freeConsumer = new KafkaConsumer<>(consumerConfig(freeGroup));
        OffsetTrackerRegistry registry = new OffsetTrackerRegistry();
        sink = new RecordingSink();

        ConsumerProperties props = new ConsumerProperties(
                proTopic, freeTopic, proGroup, freeGroup, 200, 20);

        consumerPro = new ConsumerPro(proConsumer, sink, registry, new ObjectMapper(), props);
        consumerFree = new ConsumerFree(freeConsumer, sink, registry, new ObjectMapper(), props);
    }

    @AfterEach
    void tearDown() {
        try {
            consumerPro.shutdown();
        } catch (Exception ignored) {
        }
        try {
            consumerFree.shutdown();
        } catch (Exception ignored) {
        }
        try {
            producer.close(Duration.ofSeconds(2));
        } catch (Exception ignored) {
        }
    }

    @Test
    void freeConsumerReceivesAndCommitsAfterComplete() throws Exception {
        consumerFree.start();

        String generationId = UUID.randomUUID().toString();
        producer.send(new ProducerRecord<>(freeTopic,
                generationId,
                "{\"generationId\":\"" + generationId + "\",\"tier\":\"FREE\"}")).get();

        GenerationTask task = sink.freeTasks.poll(5, TimeUnit.SECONDS);
        assertThat(task).isNotNull();
        assertThat(task.generationId().toString()).isEqualTo(generationId);

        Runnable complete = sink.freeCompletes.poll(5, TimeUnit.SECONDS);
        assertThat(complete).isNotNull();
        complete.run();

        long committed = awaitCommit(freeGroup, freeTopic);
        assertThat(committed).isGreaterThanOrEqualTo(2);
    }

    @Test
    void proConsumerReceivesAndCommitsAfterComplete() throws Exception {
        consumerPro.start();

        String generationId = UUID.randomUUID().toString();
        producer.send(new ProducerRecord<>(proTopic,
                generationId,
                "{\"generationId\":\"" + generationId + "\",\"tier\":\"PRO\"}")).get();

        GenerationTask task = sink.proTasks.poll(5, TimeUnit.SECONDS);
        assertThat(task).isNotNull();
        assertThat(task.tier()).isEqualTo(Tier.PRO);

        Runnable complete = sink.proCompletes.poll(5, TimeUnit.SECONDS);
        assertThat(complete).isNotNull();
        complete.run();

        long committed = awaitCommit(proGroup, proTopic);
        assertThat(committed).isGreaterThanOrEqualTo(2);
    }

    @Test
    void offsetNotCommittedUntilOnComplete() throws Exception {
        consumerFree.start();

        String generationId = UUID.randomUUID().toString();
        producer.send(new ProducerRecord<>(freeTopic,
                generationId,
                "{\"generationId\":\"" + generationId + "\"}")).get();

        GenerationTask task = sink.freeTasks.poll(5, TimeUnit.SECONDS);
        assertThat(task).isNotNull();

        Thread.sleep(1000);

        long committed = currentCommit(freeGroup, freeTopic);
        assertThat(committed).isLessThanOrEqualTo(1);

        Runnable complete = sink.freeCompletes.poll(5, TimeUnit.SECONDS);
        assertThat(complete).isNotNull();
        complete.run();

        long after = awaitCommit(freeGroup, freeTopic);
        assertThat(after).isGreaterThanOrEqualTo(2);
    }

    private long awaitCommit(String group, String topic) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        long last = -1;
        while (System.currentTimeMillis() < deadline) {
            last = currentCommit(group, topic);
            if (last >= 2) {
                return last;
            }
            Thread.sleep(100);
        }
        return last;
    }

    private long currentCommit(String group, String topic) {
        try (KafkaConsumer<String, String> probe = new KafkaConsumer<>(consumerConfig(group))) {
            TopicPartition tp = new TopicPartition(topic, 0);
            OffsetAndMetadata om = probe.committed(Set.of(tp)).get(tp);
            return om == null ? -1 : om.offset();
        }
    }

    private Map<String, Object> consumerConfig(String group) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers());
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG, group);
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return cfg;
    }

    static class RecordingSink implements TaskSink {

        final LinkedBlockingQueue<GenerationTask> proTasks = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<GenerationTask> freeTasks = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Runnable> proCompletes = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Runnable> freeCompletes = new LinkedBlockingQueue<>();

        @Override
        public boolean offerPro(GenerationTask task, Runnable onComplete) {
            proTasks.add(task);
            proCompletes.add(onComplete);
            return true;
        }

        @Override
        public boolean offerFree(GenerationTask task, Runnable onComplete) {
            freeTasks.add(task);
            freeCompletes.add(onComplete);
            return true;
        }

        @Override
        public boolean shouldPauseConsumer(Tier tier) {
            return false;
        }

        @Override
        public boolean shouldResumeConsumer(Tier tier) {
            return true;
        }
    }
}