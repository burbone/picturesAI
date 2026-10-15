package com.picturesAI.user_service.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeaderCurrentUserProviderTest {

    private final HeaderCurrentUserProvider provider = new HeaderCurrentUserProvider();
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void readsUserIdFromHeader() {
        UUID id = UUID.randomUUID();
        request.addHeader("X-User-Id", id.toString());

        assertThat(provider.userId()).isEqualTo(id);
    }

    @Test
    void missingUserIdThrows() {
        assertThatThrownBy(provider::userId).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void invalidUserIdThrows() {
        request.addHeader("X-User-Id", "not-a-uuid");

        assertThatThrownBy(provider::userId).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void parsesRolesFromHeader() {
        request.addHeader("X-User-Roles", "ROLE_USER, ROLE_ADMIN");

        assertThat(provider.roles()).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void emptyRolesReturnsEmptySet() {
        assertThat(provider.roles()).isEmpty();
    }

    @Test
    void noRequestContextThrows() {
        RequestContextHolder.resetRequestAttributes();

        assertThatThrownBy(provider::userId).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void readsUsernameFromHeader() {
        request.addHeader("X-User-Name", "alice");

        assertThat(provider.username()).isEqualTo("alice");
    }

    @Test
    void missingUsernameIsNull() {
        assertThat(provider.username()).isNull();
    }

    @Test
    void blankUsernameIsNull() {
        request.addHeader("X-User-Name", "   ");

        assertThat(provider.username()).isNull();
    }
}