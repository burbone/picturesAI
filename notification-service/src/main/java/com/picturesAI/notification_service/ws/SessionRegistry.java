package com.picturesAI.notification_service.ws;

import com.picturesAI.notification_service.metrics.NotificationMetrics;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRegistry {

    private final Map<UUID, Set<WebSocketSession>> generationSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();
    private final NotificationMetrics metrics;

    public SessionRegistry(NotificationMetrics metrics) {
        this.metrics = metrics;
    }

    @PostConstruct
    void bindGauges() {
        metrics.bindSessionGauges(this::generationSessionCount, this::userSessionCount);
    }

    public void registerGeneration(UUID generationId, WebSocketSession session) {
        generationSessions.computeIfAbsent(generationId, k -> ConcurrentHashMap.newKeySet()).add(session);
        metrics.sessionOpened(WebSocketSessionAttributes.SUBSCRIPTION_TYPE_GENERATION);
    }

    public void registerUser(UUID userId, WebSocketSession session) {
        userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        metrics.sessionOpened(WebSocketSessionAttributes.SUBSCRIPTION_TYPE_USER);
    }

    public void unregisterGeneration(UUID generationId, WebSocketSession session) {
        boolean removed = removeFrom(generationSessions, generationId, session);
        if (removed) {
            metrics.sessionClosed(WebSocketSessionAttributes.SUBSCRIPTION_TYPE_GENERATION, extractReason(session));
        }
    }

    public void unregisterUser(UUID userId, WebSocketSession session) {
        boolean removed = removeFrom(userSessions, userId, session);
        if (removed) {
            metrics.sessionClosed(WebSocketSessionAttributes.SUBSCRIPTION_TYPE_USER, extractReason(session));
        }
    }

    public Set<WebSocketSession> findByGenerationId(UUID generationId) {
        Set<WebSocketSession> set = generationSessions.get(generationId);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    public Set<WebSocketSession> findByUserId(UUID userId) {
        Set<WebSocketSession> set = userSessions.get(userId);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    public Set<WebSocketSession> findAllByUserId(UUID userId) {
        Set<WebSocketSession> result = new HashSet<>(findByUserId(userId));
        for (Set<WebSocketSession> set : generationSessions.values()) {
            for (WebSocketSession session : set) {
                Object attr = session.getAttributes().get(WebSocketSessionAttributes.USER_ID);
                if (userId.equals(attr)) {
                    result.add(session);
                }
            }
        }
        return Set.copyOf(result);
    }

    public int generationBucketsCount() {
        return generationSessions.size();
    }

    public int userBucketsCount() {
        return userSessions.size();
    }

    public int generationSessionCount() {
        return generationSessions.values().stream().mapToInt(Set::size).sum();
    }

    public int userSessionCount() {
        return userSessions.values().stream().mapToInt(Set::size).sum();
    }

    private boolean removeFrom(Map<UUID, Set<WebSocketSession>> map, UUID key, WebSocketSession session) {
        boolean[] removed = {false};
        map.computeIfPresent(key, (k, set) -> {
            removed[0] = set.remove(session);
            return set.isEmpty() ? null : set;
        });
        return removed[0];
    }

    private String extractReason(WebSocketSession session) {
        Object reason = session.getAttributes().get(WebSocketSessionAttributes.CLOSE_REASON);
        return reason == null ? WebSocketSessionAttributes.CLOSE_REASON_NORMAL : reason.toString();
    }
}