package com.picturesAI.user_service.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KeycloakAdminClientImplTest {

    private MockRestServiceServer server;
    private KeycloakTokenSupplier tokenSupplier;
    private KeycloakAdminClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tokenSupplier = mock(KeycloakTokenSupplier.class);
        client = new KeycloakAdminClientImpl(builder, tokenSupplier, properties());
    }

    private KeycloakProperties properties() {
        KeycloakProperties props = new KeycloakProperties();
        props.setBaseUrl("http://keycloak.local");
        props.setRealm("ai-gallery");
        return props;
    }

    @Test
    void deleteUserUsesSuppliedToken() {
        UUID keycloakId = UUID.randomUUID();
        when(tokenSupplier.token(false)).thenReturn("token-1");
        server.expect(requestTo("http://keycloak.local/admin/realms/ai-gallery/users/" + keycloakId))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer token-1"))
                .andRespond(withSuccess());

        client.deleteUser(keycloakId);

        server.verify();
    }

    @Test
    void deleteUserTreats404AsSuccess() {
        UUID keycloakId = UUID.randomUUID();
        when(tokenSupplier.token(false)).thenReturn("token-1");
        server.expect(requestTo("http://keycloak.local/admin/realms/ai-gallery/users/" + keycloakId))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        client.deleteUser(keycloakId);

        server.verify();
    }

    @Test
    void deleteUserRefreshesTokenOn401() {
        UUID keycloakId = UUID.randomUUID();
        when(tokenSupplier.token(false)).thenReturn("stale");
        when(tokenSupplier.token(true)).thenReturn("fresh");
        server.expect(requestTo("http://keycloak.local/admin/realms/ai-gallery/users/" + keycloakId))
                .andExpect(header("Authorization", "Bearer stale"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("http://keycloak.local/admin/realms/ai-gallery/users/" + keycloakId))
                .andExpect(header("Authorization", "Bearer fresh"))
                .andRespond(withSuccess());

        client.deleteUser(keycloakId);

        verify(tokenSupplier).token(true);
        server.verify();
    }

    @Test
    void deleteUserPropagatesServerError() {
        UUID keycloakId = UUID.randomUUID();
        when(tokenSupplier.token(false)).thenReturn("token-1");
        server.expect(requestTo("http://keycloak.local/admin/realms/ai-gallery/users/" + keycloakId))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.deleteUser(keycloakId))
                .isInstanceOf(RestClientResponseException.class);

        server.verify();
    }
}