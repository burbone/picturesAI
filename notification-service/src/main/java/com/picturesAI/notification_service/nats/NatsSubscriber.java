package com.picturesAI.notification_service.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.notification_service.config.NatsProperties;
import com.picturesAI.notification_service.dto.NotificationMessage;
import com.picturesAI.notification_service.dto.NotificationType;
import com.picturesAI.notification_service.metrics.NotificationMetrics;
import com.picturesAI.notification_service.ws.SessionRegistry;
import com.picturesAI.notification_service.ws.WebSocketSessionAttributes;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Component
public class NatsSubscriber {

    private static final Logger log = LoggerFactory.getLogger(NatsSubscriber.class);
    private static final CloseStatus ACCOUNT_DELETED =
            CloseStatus.NORMAL.withReason("normal closure, account deleted");

    private final Connection connection;
    private final String subject;
    private final ObjectMapper objectMapper;
    private final SessionRegistry registry;
    private final WsMessageSender sender;
    private final NotificationMetrics metrics;

    private Dispatcher dispatcher;

    public NatsSubscriber(Connection connection,
                          NatsProperties props,
                          ObjectMapper objectMapper,
                          SessionRegistry registry,
                          WsMessageSender sender,
                          NotificationMetrics metrics) {
        this.connection = connection;
        this.subject = props.subject();
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.sender = sender;
        this.metrics = metrics;
    }

    @PostConstruct
    void subscribe() {
        dispatcher = connection.createDispatcher(this::onMessage);
        dispatcher.subscribe(subject);
        log.info("Subscribed to NATS subject {}", subject);
    }

    @PreDestroy
    void unsubscribe() {
        if (dispatcher != null) {
            dispatcher.unsubscribe(subject);
        }
    }

    void onMessage(Message message) {
        NotificationMessage event;
        try {
            event = objectMapper.readValue(message.getData(), NotificationMessage.class);
        } catch (IOException e) {
            log.warn("Failed to parse NATS message on {}: {}", subject, e.getMessage());
            return;
        }
        log.info("NATS received: type={} generationId={} userId={}",
                event.type(), event.generationId(), event.userId());
        metrics.natsReceived(event.type());
        handle(event).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private Mono<Void> handle(NotificationMessage event) {
        if (event.type() == NotificationType.USER_DELETED) {
            return closeUserSessions(event.userId());
        }
        return dispatch(event);
    }

    private Mono<Void> closeUserSessions(UUID userId) {
        Set<WebSocketSession> sessions = registry.findAllByUserId(userId);
        if (sessions.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(sessions)
                .flatMap(session -> {
                    session.getAttributes().put(WebSocketSessionAttributes.CLOSE_REASON,
                            WebSocketSessionAttributes.CLOSE_REASON_ACCOUNT_DELETED);
                    return session.close(ACCOUNT_DELETED)
                            .onErrorResume(err -> {
                                log.debug("Close failed for session of user {}: {}", userId, err.getMessage());
                                return Mono.empty();
                            });
                })
                .then();
    }

    private Mono<Void> dispatch(NotificationMessage event) {
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(event);
        } catch (IOException e) {
            log.warn("Failed to serialize notification: {}", e.getMessage());
            return Mono.empty();
        }
        String body = new String(json, StandardCharsets.UTF_8);

        Set<WebSocketSession> targets = new HashSet<>();
        if (event.generationId() != null) {
            targets.addAll(registry.findByGenerationId(event.generationId()));
        }
        if (event.userId() != null) {
            targets.addAll(registry.findByUserId(event.userId()));
        }
        log.info("Dispatch: type={} generationId={} userId={} targets={}",
                event.type(), event.generationId(), event.userId(), targets.size());
        if (targets.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(targets)
                .flatMap(session -> deliver(session, body, event))
                .then();
    }

    private Mono<Void> deliver(WebSocketSession session, String body, NotificationMessage event) {
        return sender.sendText(session, body)
                .doOnSuccess(v -> metrics.notificationSent(event.type(), true))
                .onErrorResume(err -> {
                    log.debug("WS send failed, unregistering session: {}", err.getMessage());
                    metrics.notificationSent(event.type(), false);
                    session.getAttributes().put(WebSocketSessionAttributes.CLOSE_REASON,
                            WebSocketSessionAttributes.CLOSE_REASON_SEND_ERROR);
                    unregister(session, event);
                    return Mono.empty();
                });
    }

    private void unregister(WebSocketSession session, NotificationMessage event) {
        Object type = session.getAttributes().get(WebSocketSessionAttributes.SUBSCRIPTION_TYPE);
        if (WebSocketSessionAttributes.SUBSCRIPTION_TYPE_GENERATION.equals(type)) {
            Object gid = session.getAttributes().get(WebSocketSessionAttributes.GENERATION_ID);
            if (gid instanceof UUID generationId) {
                registry.unregisterGeneration(generationId, session);
                return;
            }
        }
        Object uid = session.getAttributes().get(WebSocketSessionAttributes.USER_ID);
        if (uid instanceof UUID userId) {
            registry.unregisterUser(userId, session);
        }
    }
}