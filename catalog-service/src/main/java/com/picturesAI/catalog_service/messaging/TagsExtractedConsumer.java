package com.picturesAI.catalog_service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.catalog_service.service.CatalogWriteService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TagsExtractedConsumer {

    private final ObjectMapper mapper;
    private final CatalogWriteService writer;

    public TagsExtractedConsumer(ObjectMapper mapper, CatalogWriteService writer) {
        this.mapper = mapper;
        this.writer = writer;
    }

    @KafkaListener(
            topics = "${catalog.kafka.topics.tags-extracted}",
            groupId = "${catalog.kafka.group-id}")
    public void onMessage(String payload) {
        TagsExtractedEvent event = parse(payload);
        writer.applyTagsExtracted(event);
    }

    private TagsExtractedEvent parse(String payload) {
        try {
            return mapper.readValue(payload, TagsExtractedEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid tags.extracted payload", e);
        }
    }
}