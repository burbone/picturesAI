package com.picturesAI.user_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.user_service.domain.OutboxEvent;
import com.picturesAI.user_service.domain.Plan;
import com.picturesAI.user_service.domain.Status;
import com.picturesAI.user_service.domain.User;
import com.picturesAI.user_service.repository.OutboxRepository;
import com.picturesAI.user_service.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class UserCreationService {

    static final String EVENT_USER_CREATED = "user.created";

    private final UserRepository userRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public UserCreationService(UserRepository userRepository,
                               OutboxRepository outboxRepository,
                               ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User create(UUID keycloakId, String username) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setKeycloakId(keycloakId);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPlan(Plan.FREE);
        user.setStatus(Status.ACTIVE);

        User saved = userRepository.saveAndFlush(user);

        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setAggregateId(saved.getId());
        event.setEventType(EVENT_USER_CREATED);
        event.setPayload(serializeCreatedPayload(saved));
        outboxRepository.save(event);

        return saved;
    }

    private String serializeCreatedPayload(User user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", user.getId().toString());
        payload.put("keycloakId", user.getKeycloakId().toString());
        payload.put("username", user.getUsername());
        payload.put("displayName", user.getDisplayName());
        payload.put("avatarUrl", user.getAvatarUrl());
        payload.put("createdAt", user.getCreatedAt().toString());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize user.created payload", e);
        }
    }
}