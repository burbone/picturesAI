package com.picturesAI.notification_service.ws;

import com.picturesAI.notification_service.metrics.NotificationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionRegistryTest {

    private SessionRegistry registry;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        registry = new SessionRegistry(new NotificationMetrics(meterRegistry));
        registry.bindGauges();
    }

    @Test
    void registersAndFindsGenerationSession() {
        UUID gid = UUID.randomUUID();
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getAttributes()).thenReturn(new java.util.HashMap<>());

        registry.registerGeneration(gid, s);

        assertThat(registry.findByGenerationId(gid)).containsExactly(s);
    }

    @Test
    void registersAndFindsUserSession() {
        UUID uid = UUID.randomUUID();
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getAttributes()).thenReturn(new java.util.HashMap<>());

        registry.registerUser(uid, s);

        assertThat(registry.findByUserId(uid)).containsExactly(s);
    }

    @Test
    void returnsEmptyWhenNoSessions() {
        assertThat(registry.findByGenerationId(UUID.randomUUID())).isEmpty();
        assertThat(registry.findByUserId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void multipleSessionsForSameKey() {
        UUID gid = UUID.randomUUID();
        WebSocketSession a = mock(WebSocketSession.class);
        WebSocketSession b = mock(WebSocketSession.class);
        when(a.getAttributes()).thenReturn(new java.util.HashMap<>());
        when(b.getAttributes()).thenReturn(new java.util.HashMap<>());

        registry.registerGeneration(gid, a);
        registry.registerGeneration(gid, b);

        assertThat(registry.findByGenerationId(gid)).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void unregisterRemovesSessionAndEmptyBucket() {
        UUID gid = UUID.randomUUID();
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getAttributes()).thenReturn(new java.util.HashMap<>());

        registry.registerGeneration(gid, s);
        registry.unregisterGeneration(gid, s);

        assertThat(registry.findByGenerationId(gid)).isEmpty();
        assertThat(registry.generationBucketsCount()).isZero();
    }

    @Test
    void unregisterKeepsOtherSessions() {
        UUID gid = UUID.randomUUID();
        WebSocketSession a = mock(WebSocketSession.class);
        WebSocketSession b = mock(WebSocketSession.class);
        when(a.getAttributes()).thenReturn(new java.util.HashMap<>());
        when(b.getAttributes()).thenReturn(new java.util.HashMap<>());

        registry.registerGeneration(gid, a);
        registry.registerGeneration(gid, b);
        registry.unregisterGeneration(gid, a);

        assertThat(registry.findByGenerationId(gid)).containsExactly(b);
    }

    @Test
    void unregisterUnknownKeyIsNoop() {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getAttributes()).thenReturn(new java.util.HashMap<>());

        registry.unregisterGeneration(UUID.randomUUID(), s);
        registry.unregisterUser(UUID.randomUUID(), s);

        assertThat(registry.generationBucketsCount()).isZero();
        assertThat(registry.userBucketsCount()).isZero();
    }

    @Test
    void findAllByUserIdIncludesBothGenerationAndUserSessions() {
        UUID uid = UUID.randomUUID();
        WebSocketSession userSession = mock(WebSocketSession.class);
        when(userSession.getAttributes()).thenReturn(new java.util.HashMap<>());
        WebSocketSession genSession = mock(WebSocketSession.class);
        when(genSession.getAttributes())
                .thenReturn(Map.of(WebSocketSessionAttributes.USER_ID, uid));

        registry.registerUser(uid, userSession);
        registry.registerGeneration(UUID.randomUUID(), genSession);

        assertThat(registry.findAllByUserId(uid)).containsExactlyInAnyOrder(userSession, genSession);
    }

    @Test
    void findAllByUserIdExcludesOtherUsers() {
        UUID uid = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        WebSocketSession genSession = mock(WebSocketSession.class);
        when(genSession.getAttributes())
                .thenReturn(Map.of(WebSocketSessionAttributes.USER_ID, other));

        registry.registerGeneration(UUID.randomUUID(), genSession);

        assertThat(registry.findAllByUserId(uid)).isEmpty();
    }

    @Test
    void sessionCountsAggregateAcrossBuckets() {
        UUID g1 = UUID.randomUUID();
        UUID g2 = UUID.randomUUID();
        WebSocketSession a = mock(WebSocketSession.class);
        WebSocketSession b = mock(WebSocketSession.class);
        WebSocketSession c = mock(WebSocketSession.class);
        when(a.getAttributes()).thenReturn(new java.util.HashMap<>());
        when(b.getAttributes()).thenReturn(new java.util.HashMap<>());
        when(c.getAttributes()).thenReturn(new java.util.HashMap<>());

        registry.registerGeneration(g1, a);
        registry.registerGeneration(g1, b);
        registry.registerGeneration(g2, c);

        assertThat(registry.generationSessionCount()).isEqualTo(3);
        assertThat(registry.generationBucketsCount()).isEqualTo(2);
    }

    @Test
    void metricsIncrementedOnOpenAndClose() {
        UUID gid = UUID.randomUUID();
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getAttributes()).thenReturn(new java.util.HashMap<>());

        registry.registerGeneration(gid, s);
        registry.unregisterGeneration(gid, s);

        assertThat(meterRegistry.get("ws_sessions_opened_total")
                .tag("type", "generation").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("ws_sessions_closed_total")
                .tag("type", "generation").tag("reason", "normal").counter().count()).isEqualTo(1.0);
    }

    @Test
    void metricsTrackAccountDeletedReason() {
        UUID uid = UUID.randomUUID();
        WebSocketSession s = mock(WebSocketSession.class);
        java.util.HashMap<String, Object> attrs = new java.util.HashMap<>();
        attrs.put(WebSocketSessionAttributes.CLOSE_REASON,
                WebSocketSessionAttributes.CLOSE_REASON_ACCOUNT_DELETED);
        when(s.getAttributes()).thenReturn(attrs);

        registry.registerUser(uid, s);
        registry.unregisterUser(uid, s);

        assertThat(meterRegistry.get("ws_sessions_closed_total")
                .tag("type", "user").tag("reason", "account_deleted").counter().count()).isEqualTo(1.0);
    }

    @Test
    void gaugeReflectsActiveSessions() {
        UUID gid = UUID.randomUUID();
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getAttributes()).thenReturn(new java.util.HashMap<>());

        registry.registerGeneration(gid, s);

        assertThat(meterRegistry.get("ws_active_sessions")
                .tag("type", "generation").gauge().value()).isEqualTo(1.0);
    }
}