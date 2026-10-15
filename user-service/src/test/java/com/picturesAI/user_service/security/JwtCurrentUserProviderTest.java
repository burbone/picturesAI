package com.picturesAI.user_service.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtCurrentUserProviderTest {

    private final JwtCurrentUserProvider provider = new JwtCurrentUserProvider();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Jwt jwt(String sub) {
        return new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("sub", sub));
    }

    private Jwt jwtWithName(String sub, String name) {
        return new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("sub", sub, "preferred_username", name));
    }

    private void authenticate(Jwt jwt, String... roles) {
        List<SimpleGrantedAuthority> authorities = List.of(roles).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, authorities));
    }

    @Test
    void readsSubAsUuid() {
        UUID id = UUID.randomUUID();
        authenticate(jwt(id.toString()), "ROLE_USER");

        assertThat(provider.userId()).isEqualTo(id);
    }

    @Test
    void noAuthenticationThrows() {
        assertThatThrownBy(provider::userId).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void subNotUuidThrows() {
        authenticate(jwt("not-a-uuid"), "ROLE_USER");

        assertThatThrownBy(provider::userId).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void returnsAuthoritiesAsRoles() {
        authenticate(jwt(UUID.randomUUID().toString()), "ROLE_USER", "ROLE_ADMIN");

        assertThat(provider.roles()).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void noAuthenticationRolesEmpty() {
        assertThat(provider.roles()).isEmpty();
    }

    @Test
    void readsUsernameFromClaim() {
        authenticate(jwtWithName(UUID.randomUUID().toString(), "alice"), "ROLE_USER");

        assertThat(provider.username()).isEqualTo("alice");
    }

    @Test
    void missingUsernameClaimIsNull() {
        authenticate(jwt(UUID.randomUUID().toString()), "ROLE_USER");

        assertThat(provider.username()).isNull();
    }
}