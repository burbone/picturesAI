package com.picturesAI.catalog_service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.catalog_service.service.CatalogWriteService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class UserCreatedConsumer {

    private final ObjectMapper mapper;
    private final CatalogWriteService writer;

    public UserCreatedConsumer(ObjectMapper mapper, CatalogWriteService writer) {
        this.mapper = mapper;
        this.writer = writer;
    }

    @KafkaListener(
            topics = "${catalog.kafka.topics.user-created}",
            groupId = "${catalog.kafka.group-id}")
    public void onMessage(String payload) {
        UserCreatedEvent event = parse(payload);
        writer.applyUserCreated(event);
    }

    private UserCreatedEvent parse(String payload) {
        try {
            return mapper.readValue(payload, UserCreatedEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid user.created payload", e);
        }
    }
}