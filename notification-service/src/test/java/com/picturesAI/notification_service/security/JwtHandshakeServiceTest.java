package com.picturesAI.notification_service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtHandshakeServiceTest {

    private JwtDecoder jwtDecoder;
    private JwtHandshakeService service;

    @BeforeEach
    void setUp() {
        jwtDecoder = mock(JwtDecoder.class);
        service = new JwtHandshakeService(jwtDecoder);
    }

    private Jwt jwtFor(UUID userId, String username, List<String> roles) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject(userId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (username != null) {
            builder.claim("preferred_username", username);
        }
        if (roles != null) {
            builder.claim("realm_access", Map.of("roles", roles));
        }
        return builder.build();
    }

    @Test
    void validUserChannel() {
        UUID userId = UUID.randomUUID();
        when(jwtDecoder.decode("good")).thenReturn(jwtFor(userId, "alice", List.of("user", "admin")));

        StepVerifier.create(service.authenticate(URI.create("ws://x/ws/notifications?token=good")))
                .assertNext(r -> {
                    assertThat(r.hasError()).isFalse();
                    assertThat(r.subscriptionType()).isEqualTo(HandshakeResult.SubscriptionType.USER);
                    assertThat(r.userId()).isEqualTo(userId);
                    assertThat(r.username()).isEqualTo("alice");
                    assertThat(r.roles()).containsExactlyInAnyOrder("user", "admin");
                    assertThat(r.generationId()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void validGenerationChannelReturnsGenerationWithoutHttpCall() {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        when(jwtDecoder.decode("good")).thenReturn(jwtFor(userId, "alice", List.of("user")));

        StepVerifier.create(service.authenticate(URI.create("ws://x/ws/generations/" + generationId + "?token=good")))
                .assertNext(r -> {
                    assertThat(r.hasError()).isFalse();
                    assertThat(r.subscriptionType()).isEqualTo(HandshakeResult.SubscriptionType.GENERATION);
                    assertThat(r.generationId()).isEqualTo(generationId);
                    assertThat(r.userId()).isEqualTo(userId);
                })
                .verifyComplete();
    }

    @Test
    void missingToken() {
        StepVerifier.create(service.authenticate(URI.create("ws://x/ws/notifications")))
                .assertNext(r -> assertThat(r.error()).isEqualTo(HandshakeResult.AccessError.UNAUTHORIZED))
                .verifyComplete();
    }

    @Test
    void blankToken() {
        StepVerifier.create(service.authenticate(URI.create("ws://x/ws/notifications?token=")))
                .assertNext(r -> assertThat(r.error()).isEqualTo(HandshakeResult.AccessError.UNAUTHORIZED))
                .verifyComplete();
    }

    @Test
    void invalidToken() {
        when(jwtDecoder.decode(anyString())).thenThrow(new JwtException("bad"));

        StepVerifier.create(service.authenticate(URI.create("ws://x/ws/notifications?token=bad")))
                .assertNext(r -> assertThat(r.error()).isEqualTo(HandshakeResult.AccessError.UNAUTHORIZED))
                .verifyComplete();
    }

    @Test
    void nonUuidSubject() {
        Jwt spoofed = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("not-a-uuid")
                .claim("preferred_username", "bob")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(spoofed);

        StepVerifier.create(service.authenticate(URI.create("ws://x/ws/notifications?token=t")))
                .assertNext(r -> assertThat(r.error()).isEqualTo(HandshakeResult.AccessError.UNAUTHORIZED))
                .verifyComplete();
    }

    @Test
    void invalidPath() {
        StepVerifier.create(service.authenticate(URI.create("ws://x/ws/unknown?token=good")))
                .assertNext(r -> assertThat(r.error()).isEqualTo(HandshakeResult.AccessError.UNAUTHORIZED))
                .verifyComplete();
    }

    @Test
    void generationPathWithNonUuid() {
        StepVerifier.create(service.authenticate(URI.create("ws://x/ws/generations/not-a-uuid?token=good")))
                .assertNext(r -> assertThat(r.error()).isEqualTo(HandshakeResult.AccessError.UNAUTHORIZED))
                .verifyComplete();
    }

    @Test
    void missingRealmAccessYieldsEmptyRoles() {
        UUID userId = UUID.randomUUID();
        when(jwtDecoder.decode(anyString())).thenReturn(jwtFor(userId, "carol", null));

        StepVerifier.create(service.authenticate(URI.create("ws://x/ws/notifications?token=t")))
                .assertNext(r -> {
                    assertThat(r.hasError()).isFalse();
                    assertThat(r.roles()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void tokenExtractedWhenNotFirstParam() {
        UUID userId = UUID.randomUUID();
        when(jwtDecoder.decode("good")).thenReturn(jwtFor(userId, "alice", List.of("user")));

        StepVerifier.create(service.authenticate(URI.create("ws://x/ws/notifications?foo=bar&token=good")))
                .assertNext(r -> assertThat(r.hasError()).isFalse())
                .verifyComplete();
    }
}