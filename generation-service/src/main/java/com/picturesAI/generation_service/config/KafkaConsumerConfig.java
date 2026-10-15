package com.picturesAI.generation_service.config;

import com.picturesAI.generation_service.worker.consumer.ConsumerProperties;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.HashMap;
import java.util.Map;

@Configuration
@Profile("!test")
public class KafkaConsumerConfig {

    @Bean(destroyMethod = "close")
    public KafkaConsumer<String, String> proKafkaConsumer(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            ConsumerProperties props) {
        return new KafkaConsumer<>(config(bootstrapServers, props.proGroupId(), 50, 30000));
    }

    @Bean(destroyMethod = "close")
    public KafkaConsumer<String, String> freeKafkaConsumer(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            ConsumerProperties props) {
        return new KafkaConsumer<>(config(bootstrapServers, props.freeGroupId(), 50, 30000));
    }

    @Bean(destroyMethod = "close")
    public KafkaConsumer<String, String> userDeletedKafkaConsumer(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new KafkaConsumer<>(config(bootstrapServers, "generation-service-user-deleted", 10, 30000));
    }

    private Map<String, Object> config(String bootstrapServers,
                                       String groupId,
                                       int maxPollRecords,
                                       int maxPollIntervalMs) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG, groupId);
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs);
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        cfg.put(org.apache.kafka.clients.consumer.ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
        return cfg;
    }
}