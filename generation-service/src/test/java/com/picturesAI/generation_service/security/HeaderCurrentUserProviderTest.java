package com.picturesAI.generation_service.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeaderCurrentUserProviderTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private final HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider(request);

    @Test
    void readsUserIdFromHeader() {
        UUID id = UUID.randomUUID();
        when(request.getHeader("X-User-Id")).thenReturn(id.toString());

        assertThat(provider.userId()).isEqualTo(id);
    }

    @Test
    void returnsNullUserIdWhenHeaderMissing() {
        assertThat(provider.userId()).isNull();
    }

    @Test
    void parsesRolesCsvWithSpaces() {
        when(request.getHeader("X-User-Roles")).thenReturn("ROLE_USER, ROLE_ADMIN");

        assertThat(provider.roles()).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void returnsEmptyRolesWhenHeaderMissing() {
        assertThat(provider.roles()).isEmpty();
    }

    @Test
    void returnsEmptyRolesWhenHeaderBlank() {
        when(request.getHeader("X-User-Roles")).thenReturn("   ");

        assertThat(provider.roles()).isEmpty();
    }

    @Test
    void readsUsernameFromHeader() {
        when(request.getHeader("X-User-Name")).thenReturn("alice");

        assertThat(provider.username()).isEqualTo("alice");
    }

    @Test
    void returnsNullUsernameWhenHeaderMissing() {
        assertThat(provider.username()).isNull();
    }
}
