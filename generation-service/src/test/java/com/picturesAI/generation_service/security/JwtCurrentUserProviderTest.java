package com.picturesAI.generation_service.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtCurrentUserProviderTest {

    private final JwtCurrentUserProvider provider = new JwtCurrentUserProvider();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsUserIdFromSubClaim() {
        UUID id = UUID.randomUUID();
        setJwt(Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(id.toString())
                .claim("preferred_username", "alice")
                .claim("realm_access", Map.of("roles", List.of("user", "admin")))
                .build());

        assertThat(provider.userId()).isEqualTo(id);
        assertThat(provider.username()).isEqualTo("alice");
        assertThat(provider.roles()).containsExactlyInAnyOrder("user", "admin");
    }

    @Test
    void returnsEmptyRolesWhenRealmAccessMissing() {
        setJwt(Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .build());

        assertThat(provider.roles()).isEmpty();
    }

    @Test
    void returnsEmptyRolesWhenRolesClaimNotCollection() {
        setJwt(Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("realm_access", Map.of("roles", "not-a-collection"))
                .build());

        assertThat(provider.roles()).isEmpty();
    }

    @Test
    void throwsWhenNoAuthenticationPresent() {
        assertThatThrownBy(provider::userId)
                .isInstanceOf(IllegalStateException.class);
    }

    private void setJwt(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}