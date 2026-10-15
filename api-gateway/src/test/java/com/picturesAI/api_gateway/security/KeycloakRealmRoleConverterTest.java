package com.picturesAI.api_gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void mapsRealmRolesToRoleAuthorities() {
        Jwt jwt = jwtWithRealmRoles(List.of("user", "admin"));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_user", "ROLE_admin");
    }

    @Test
    void returnsEmptyWhenRealmAccessMissing() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("u1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void returnsEmptyWhenRolesNotACollection() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("u1")
                .claim("realm_access", Map.of("roles", "not-a-list"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void ignoresNonStringRoles() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("u1")
                .claim("realm_access", Map.of("roles", List.of("user", 42)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_user");
    }

    private Jwt jwtWithRealmRoles(List<String> roles) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("u1")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}