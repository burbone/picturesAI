package com.picturesAI.catalog_service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.catalog_service.service.CatalogWriteService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class UserDeletedConsumer {

    private final ObjectMapper mapper;
    private final CatalogWriteService writer;

    public UserDeletedConsumer(ObjectMapper mapper, CatalogWriteService writer) {
        this.mapper = mapper;
        this.writer = writer;
    }

    @KafkaListener(
            topics = "${catalog.kafka.topics.user-deleted}",
            groupId = "${catalog.kafka.group-id}")
    public void onMessage(String payload) {
        UserDeletedEvent event = parse(payload);
        writer.applyUserDeleted(event);
    }

    private UserDeletedEvent parse(String payload) {
        try {
            return mapper.readValue(payload, UserDeletedEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid user.deleted payload", e);
        }
    }
}