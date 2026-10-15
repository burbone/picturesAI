package com.picturesAI.notification_service.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.notification_service.config.NatsProperties;
import com.picturesAI.notification_service.metrics.NotificationMetrics;
import com.picturesAI.notification_service.ws.SessionRegistry;
import com.picturesAI.notification_service.ws.WebSocketSessionAttributes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NatsSubscriberTest {

    private Connection connection;
    private Dispatcher dispatcher;
    private SessionRegistry registry;
    private WsMessageSender sender;
    private NatsSubscriber subscriber;
    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        dispatcher = mock(Dispatcher.class);
        when(connection.createDispatcher(any())).thenReturn(dispatcher);

        meterRegistry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new NotificationMetrics(meterRegistry);
        registry = new SessionRegistry(metrics);
        sender = mock(WsMessageSender.class);
        objectMapper = new ObjectMapper();

        NatsProperties props = new NatsProperties(
                "nats://x", "u", "p", "notification.push");
        subscriber = new NatsSubscriber(connection, props, objectMapper, registry, sender, metrics);
    }

    private Message message(String json) {
        Message m = mock(Message.class);
        when(m.getData()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        return m;
    }

    private WebSocketSession wsSession(String type, UUID key) {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(WebSocketSessionAttributes.SUBSCRIPTION_TYPE, type);
        if (WebSocketSessionAttributes.SUBSCRIPTION_TYPE_GENERATION.equals(type)) {
            attrs.put(WebSocketSessionAttributes.GENERATION_ID, key);
        } else {
            attrs.put(WebSocketSessionAttributes.USER_ID, key);
        }
        when(s.getAttributes()).thenReturn(attrs);
        when(s.close(any(CloseStatus.class))).thenReturn(Mono.empty());
        return s;
    }

    @Test
    void deliverToGenerationSubscriber() throws Exception {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        WebSocketSession session = wsSession(WebSocketSessionAttributes.SUBSCRIPTION_TYPE_GENERATION, generationId);
        registry.registerGeneration(generationId, session);
        when(sender.sendText(eq(session), anyString())).thenReturn(Mono.empty());

        String json = "{\"type\":\"generation.completed\",\"generationId\":\"" + generationId +
                "\",\"userId\":\"" + userId + "\",\"payload\":{\"imageUrl\":\"x\"}}";
        subscriber.onMessage(message(json));

        Thread.sleep(300);

        verify(sender).sendText(eq(session), anyString());
    }

    @Test
    void deliverToUserSubscriber() throws Exception {
        UUID userId = UUID.randomUUID();
        WebSocketSession session = wsSession(WebSocketSessionAttributes.SUBSCRIPTION_TYPE_USER, userId);
        registry.registerUser(userId, session);
        when(sender.sendText(eq(session), anyString())).thenReturn(Mono.empty());

        String json = "{\"type\":\"generation.failed\",\"generationId\":\"" + UUID.randomUUID() +
                "\",\"userId\":\"" + userId + "\",\"payload\":{\"errorMessage\":\"boom\"}}";
        subscriber.onMessage(message(json));

        Thread.sleep(300);

        verify(sender).sendText(eq(session), anyString());
    }

    @Test
    void deliversToBothGenerationAndUserSubscribers() throws Exception {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        WebSocketSession genSession = wsSession(WebSocketSessionAttributes.SUBSCRIPTION_TYPE_GENERATION, generationId);
        WebSocketSession userSession = wsSession(WebSocketSessionAttributes.SUBSCRIPTION_TYPE_USER, userId);
        registry.registerGeneration(generationId, genSession);
        registry.registerUser(userId, userSession);
        when(sender.sendText(eq(genSession), anyString())).thenReturn(Mono.empty());
        when(sender.sendText(eq(userSession), anyString())).thenReturn(Mono.empty());

        String json = "{\"type\":\"generation.completed\",\"generationId\":\"" + generationId +
                "\",\"userId\":\"" + userId + "\",\"payload\":{}}";
        subscriber.onMessage(message(json));

        Thread.sleep(300);

        verify(sender).sendText(eq(genSession), anyString());
        verify(sender).sendText(eq(userSession), anyString());
    }

    @Test
    void ignoresWhenNoSubscribers() throws Exception {
        String json = "{\"type\":\"generation.completed\",\"generationId\":\"" + UUID.randomUUID() +
                "\",\"userId\":\"" + UUID.randomUUID() + "\",\"payload\":{}}";
        subscriber.onMessage(message(json));

        Thread.sleep(200);

        assertThat(registry.generationSessionCount()).isZero();
        assertThat(registry.userSessionCount()).isZero();
    }

    @Test
    void ignoresMalformedJson() {
        subscriber.onMessage(message("{not json}"));

        assertThat(registry.generationSessionCount()).isZero();
    }

    @Test
    void sendFailureUnregistersSession() throws Exception {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(WebSocketSessionAttributes.SUBSCRIPTION_TYPE, WebSocketSessionAttributes.SUBSCRIPTION_TYPE_GENERATION);
        attrs.put(WebSocketSessionAttributes.GENERATION_ID, generationId);
        attrs.put(WebSocketSessionAttributes.USER_ID, userId);
        when(session.getAttributes()).thenReturn(attrs);

        registry.registerGeneration(generationId, session);
        when(sender.sendText(eq(session), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("closed")));

        String json = "{\"type\":\"generation.completed\",\"generationId\":\"" + generationId +
                "\",\"userId\":\"" + userId + "\",\"payload\":{}}";
        subscriber.onMessage(message(json));

        Thread.sleep(300);

        assertThat(registry.findByGenerationId(generationId)).isEmpty();
        assertThat(meterRegistry.get("notification_send_total")
                .tag("type", "generation.completed").tag("result", "error").counter().count()).isEqualTo(1.0);
    }

    @Test
    void subscribeCalledOnPostConstruct() {
        subscriber.subscribe();

        verify(dispatcher).subscribe("notification.push");
    }

    @Test
    void userDeletedClosesAllSessionsOfUser() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();

        WebSocketSession userSession = wsSession(WebSocketSessionAttributes.SUBSCRIPTION_TYPE_USER, userId);
        WebSocketSession genSession = mock(WebSocketSession.class);
        Map<String, Object> genAttrs = new HashMap<>();
        genAttrs.put(WebSocketSessionAttributes.SUBSCRIPTION_TYPE, WebSocketSessionAttributes.SUBSCRIPTION_TYPE_GENERATION);
        genAttrs.put(WebSocketSessionAttributes.GENERATION_ID, generationId);
        genAttrs.put(WebSocketSessionAttributes.USER_ID, userId);
        when(genSession.getAttributes()).thenReturn(genAttrs);
        when(genSession.close(any(CloseStatus.class))).thenReturn(Mono.empty());

        registry.registerUser(userId, userSession);
        registry.registerGeneration(generationId, genSession);

        String json = "{\"type\":\"user.deleted\",\"generationId\":null,\"userId\":\"" + userId +
                "\",\"payload\":{\"keycloakId\":\"kc-1\"}}";
        subscriber.onMessage(message(json));

        Thread.sleep(300);

        verify(userSession).close(ArgumentMatchers.<CloseStatus>argThat(
                status -> status.getCode() == 1000 && "normal closure, account deleted".equals(status.getReason())));
        verify(genSession).close(ArgumentMatchers.<CloseStatus>argThat(
                status -> status.getCode() == 1000 && "normal closure, account deleted".equals(status.getReason())));
        verify(sender, never()).sendText(any(), anyString());
    }

    @Test
    void userDeletedWithNoSessionsIsNoop() throws Exception {
        String json = "{\"type\":\"user.deleted\",\"generationId\":null,\"userId\":\"" + UUID.randomUUID() +
                "\",\"payload\":{}}";
        subscriber.onMessage(message(json));

        Thread.sleep(200);

        verify(sender, never()).sendText(any(), anyString());
    }
}