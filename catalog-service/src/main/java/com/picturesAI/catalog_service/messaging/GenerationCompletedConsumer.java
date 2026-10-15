package com.picturesAI.catalog_service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.catalog_service.service.CatalogWriteService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class GenerationCompletedConsumer {

    private final ObjectMapper mapper;
    private final CatalogWriteService writer;

    public GenerationCompletedConsumer(ObjectMapper mapper, CatalogWriteService writer) {
        this.mapper = mapper;
        this.writer = writer;
    }

    @KafkaListener(
            topics = "${catalog.kafka.topics.generation-completed}",
            groupId = "${catalog.kafka.group-id}")
    public void onMessage(String payload) {
        GenerationCompletedEvent event = parse(payload);
        writer.applyGenerationCompleted(event);
    }

    private GenerationCompletedEvent parse(String payload) {
        try {
            return mapper.readValue(payload, GenerationCompletedEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid generation.completed payload", e);
        }
    }
}