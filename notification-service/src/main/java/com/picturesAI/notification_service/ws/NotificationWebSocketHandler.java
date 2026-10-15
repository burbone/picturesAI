package com.picturesAI.notification_service.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.notification_service.dto.NotificationMessage;
import com.picturesAI.notification_service.dto.NotificationType;
import com.picturesAI.notification_service.integration.GenerationAccessResult;
import com.picturesAI.notification_service.integration.GenerationServiceClient;
import com.picturesAI.notification_service.integration.GenerationStatusResult;
import com.picturesAI.notification_service.security.HandshakeResult;
import com.picturesAI.notification_service.security.JwtHandshakeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Component
public class NotificationWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationWebSocketHandler.class);

    private static final CloseStatus UNAUTHORIZED = CloseStatus.POLICY_VIOLATION.withReason("unauthorized");
    private static final CloseStatus NOT_FOUND = CloseStatus.POLICY_VIOLATION.withReason("not found");
    private static final CloseStatus FORBIDDEN = CloseStatus.POLICY_VIOLATION.withReason("forbidden");
    private static final CloseStatus INTERNAL_ERROR = CloseStatus.SERVER_ERROR.withReason("internal error");

    private static final String PING_PAYLOAD = "{\"type\":\"ping\"}";

    private final JwtHandshakeService handshakeService;
    private final GenerationServiceClient generationServiceClient;
    private final SessionRegistry registry;
    private final ObjectMapper objectMapper;
    private final Duration heartbeatInterval;

    public NotificationWebSocketHandler(JwtHandshakeService handshakeService,
                                        GenerationServiceClient generationServiceClient,
                                        SessionRegistry registry,
                                        ObjectMapper objectMapper,
                                        @Value("${notification.ws.heartbeat-interval-seconds:20}") long heartbeatIntervalSeconds) {
        this.handshakeService = handshakeService;
        this.generationServiceClient = generationServiceClient;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.heartbeatInterval = Duration.ofSeconds(heartbeatIntervalSeconds);
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        URI uri = session.getHandshakeInfo().getUri();
        return handshakeService.authenticate(uri)
                .flatMap(result -> result.hasError()
                        ? session.close(mapError(result.error()))
                        : establish(session, result));
    }

    private Mono<Void> establish(WebSocketSession session, HandshakeResult result) {
        session.getAttributes().put(WebSocketSessionAttributes.USER_ID, result.userId());
        session.getAttributes().put(WebSocketSessionAttributes.USERNAME, result.username());
        session.getAttributes().put(WebSocketSessionAttributes.ROLES, result.roles());

        Sinks.Many<WebSocketMessage> outbound = Sinks.many().multicast().onBackpressureBuffer();
        session.getAttributes().put(WebSocketSessionAttributes.OUTBOUND_SINK, outbound);

        if (result.subscriptionType() == HandshakeResult.SubscriptionType.GENERATION) {
            return establishGeneration(session, result, outbound);
        }

        session.getAttributes().put(WebSocketSessionAttributes.SUBSCRIPTION_TYPE,
                WebSocketSessionAttributes.SUBSCRIPTION_TYPE_USER);
        registry.registerUser(result.userId(), session);
        return runSession(session, outbound,
                () -> registry.unregisterUser(result.userId(), session),
                null);
    }

    private Mono<Void> establishGeneration(WebSocketSession session, HandshakeResult result,
                                           Sinks.Many<WebSocketMessage> outbound) {
        UUID generationId = result.generationId();
        session.getAttributes().put(WebSocketSessionAttributes.SUBSCRIPTION_TYPE,
                WebSocketSessionAttributes.SUBSCRIPTION_TYPE_GENERATION);
        session.getAttributes().put(WebSocketSessionAttributes.GENERATION_ID, generationId);

        registry.registerGeneration(generationId, session);

        return generationServiceClient.fetchStatus(generationId, result.userId(), result.roles(), result.username())
                .flatMap(status -> handleStatus(session, result, status, outbound))
                .onErrorResume(err -> {
                    log.warn("fetchStatus error for generation {}: {}", generationId, err.getMessage());
                    registry.unregisterGeneration(generationId, session);
                    return session.close(INTERNAL_ERROR);
                });
    }

    private Mono<Void> handleStatus(WebSocketSession session, HandshakeResult result,
                                    GenerationStatusResult status, Sinks.Many<WebSocketMessage> outbound) {
        if (status.access() == GenerationAccessResult.NOT_FOUND) {
            registry.unregisterGeneration(result.generationId(), session);
            return session.close(NOT_FOUND);
        }
        if (status.access() == GenerationAccessResult.FORBIDDEN) {
            registry.unregisterGeneration(result.generationId(), session);
            return session.close(FORBIDDEN);
        }
        if (status.access() == GenerationAccessResult.INTERNAL_ERROR) {
            registry.unregisterGeneration(result.generationId(), session);
            return session.close(INTERNAL_ERROR);
        }

        NotificationMessage initialState = buildInitialState(result, status.payload());
        return runSession(session, outbound,
                () -> registry.unregisterGeneration(result.generationId(), session),
                initialState);
    }

    private NotificationMessage buildInitialState(HandshakeResult result, JsonNode payload) {
        if (payload == null || !payload.hasNonNull("status")) {
            return null;
        }
        NotificationType type = mapStatus(payload.get("status").asText());
        if (type == null) {
            return null;
        }
        Map<String, Object> payloadMap = objectMapper.convertValue(payload,
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        return new NotificationMessage(type, result.generationId(), result.userId(), payloadMap);
    }

    private NotificationType mapStatus(String status) {
        return switch (status) {
            case "QUEUED" -> NotificationType.GENERATION_QUEUED;
            case "PROCESSING" -> NotificationType.GENERATION_PROCESSING;
            case "COMPLETED" -> NotificationType.GENERATION_COMPLETED;
            case "FAILED" -> NotificationType.GENERATION_FAILED;
            case "CANCELLED" -> NotificationType.GENERATION_CANCELLED;
            default -> null;
        };
    }

    private Mono<Void> runSession(WebSocketSession session,
                                  Sinks.Many<WebSocketMessage> outbound,
                                  Runnable unregister,
                                  NotificationMessage initialState) {
        Sinks.Empty<Void> sessionClosed = Sinks.empty();

        Mono<Void> sendMono = session.send(outbound.asFlux())
                .doFinally(signal -> sessionClosed.tryEmitEmpty());

        Mono<Void> receiveMono = session.receive()
                .doFinally(signal -> {
                    unregister.run();
                    sessionClosed.tryEmitEmpty();
                    outbound.tryEmitComplete();
                })
                .then();

        Mono<Void> heartbeatMono = Flux.interval(heartbeatInterval)
                .map(i -> session.textMessage(PING_PAYLOAD))
                .takeUntilOther(sessionClosed.asMono())
                .doOnNext(msg -> {
                    Sinks.EmitResult r = outbound.tryEmitNext(msg);
                    if (r.isFailure()) {
                        log.warn("Heartbeat emit failed: {}", r);
                    }
                })
                .then();

        if (initialState != null) {
            try {
                String json = objectMapper.writeValueAsString(initialState);
                Sinks.EmitResult r = outbound.tryEmitNext(session.textMessage(json));
                if (r.isFailure()) {
                    log.warn("Initial emit failed: {}", r);
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize initial state: {}", e.getMessage());
            }
        }

        return Mono.when(sendMono, receiveMono, heartbeatMono);
    }

    private CloseStatus mapError(HandshakeResult.AccessError error) {
        return switch (error) {
            case UNAUTHORIZED -> UNAUTHORIZED;
            case NOT_FOUND -> NOT_FOUND;
            case FORBIDDEN -> FORBIDDEN;
            case INTERNAL_ERROR -> INTERNAL_ERROR;
        };
    }
}