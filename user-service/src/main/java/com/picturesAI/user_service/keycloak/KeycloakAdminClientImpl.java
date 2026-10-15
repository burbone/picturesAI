package com.picturesAI.user_service.keycloak;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

@Component
public class KeycloakAdminClientImpl implements KeycloakAdminClient {

    private final RestClient restClient;
    private final KeycloakTokenSupplier tokenSupplier;
    private final KeycloakProperties properties;

    public KeycloakAdminClientImpl(RestClient.Builder builder,
                                   KeycloakTokenSupplier tokenSupplier,
                                   KeycloakProperties properties) {
        this.restClient = builder.baseUrl(properties.getBaseUrl()).build();
        this.tokenSupplier = tokenSupplier;
        this.properties = properties;
    }

    @Override
    public void deleteUser(UUID keycloakId) {
        try {
            doDelete(keycloakId, tokenSupplier.token(false));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                doDelete(keycloakId, tokenSupplier.token(true));
                return;
            }
            throw e;
        }
    }

    private void doDelete(UUID keycloakId, String token) {
        try {
            restClient.delete()
                    .uri("/admin/realms/{realm}/users/{id}", properties.getRealm(), keycloakId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return;
            }
            throw e;
        }
    }
}