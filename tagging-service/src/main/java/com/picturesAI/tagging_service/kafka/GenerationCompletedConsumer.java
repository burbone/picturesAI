package com.picturesAI.tagging_service.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.tagging_service.service.TaggingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class GenerationCompletedConsumer {

    private static final Logger log = LoggerFactory.getLogger(GenerationCompletedConsumer.class);

    private final TaggingService taggingService;
    private final ObjectMapper mapper;

    public GenerationCompletedConsumer(TaggingService taggingService, ObjectMapper mapper) {
        this.taggingService = taggingService;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "generation.completed", groupId = "tagging-service")
    public void onGenerationCompleted(String payload, Acknowledgment ack) {
        JsonNode node;
        try {
            node = mapper.readTree(payload);
        } catch (Exception e) {
            log.error("Malformed generation.completed payload, skipping: {}", payload, e);
            ack.acknowledge();
            return;
        }

        String generationIdRaw = node.path("generationId").asText(null);
        if (generationIdRaw == null || generationIdRaw.isBlank()) {
            log.error("generation.completed missing generationId, skipping: {}", payload);
            ack.acknowledge();
            return;
        }

        UUID generationId;
        try {
            generationId = UUID.fromString(generationIdRaw);
        } catch (Exception e) {
            log.error("generation.completed invalid generationId '{}', skipping", generationIdRaw);
            ack.acknowledge();
            return;
        }

        UUID userId = null;
        String userIdRaw = node.path("userId").asText(null);
        if (userIdRaw != null && !userIdRaw.isBlank()) {
            try {
                userId = UUID.fromString(userIdRaw);
            } catch (Exception e) {
                log.warn("generation.completed invalid userId '{}' for generation {}, ignoring",
                        userIdRaw, generationId);
            }
        }

        String prompt = node.path("prompt").asText(null);
        if (prompt == null) {
            log.warn("generation.completed missing prompt for generation {}, will extract empty tags",
                    generationId);
        }

        try {
            taggingService.process(generationId, userId, prompt);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to tag generation {}: {}", generationId, e.toString(), e);
        }
    }
}