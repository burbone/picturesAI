package com.picturesAI.user_service.deletion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.user_service.domain.OutboxEvent;
import com.picturesAI.user_service.domain.Status;
import com.picturesAI.user_service.domain.User;
import com.picturesAI.user_service.repository.OutboxRepository;
import com.picturesAI.user_service.repository.UserRepository;
import com.picturesAI.user_service.service.UserNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserDeletionService {

    static final String EVENT_USER_DELETED = "user.deleted";

    private final UserRepository userRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public UserDeletionService(UserRepository userRepository,
                               OutboxRepository outboxRepository,
                               ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void initiate(UUID keycloakId) {
        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new UserNotFoundException("Profile not found"));
        if (user.getStatus() == Status.DELETING) {
            return;
        }
        user.setStatus(Status.DELETING);
        userRepository.saveAndFlush(user);

        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setAggregateId(user.getId());
        event.setEventType(EVENT_USER_DELETED);
        event.setPayload(serializeDeletedPayload(user));
        outboxRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<User> listPendingDeletions(int batchSize) {
        return userRepository.findByStatus(Status.DELETING, PageRequest.of(0, batchSize));
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return userRepository.countByStatus(Status.DELETING);
    }

    @Transactional
    public boolean markAttempt(UUID userId) {
        return userRepository.touchForDeletion(userId, Status.DELETING, Instant.now()) > 0;
    }

    @Transactional
    public void finalizeDeletion(UUID userId) {
        if (userRepository.existsById(userId)) {
            userRepository.deleteById(userId);
        }
    }

    private String serializeDeletedPayload(User user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", user.getId().toString());
        payload.put("keycloakId", user.getKeycloakId().toString());
        payload.put("deletedAt", Instant.now().toString());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize user.deleted payload", e);
        }
    }
}