package com.picturesAI.user_service.keycloak;

public interface KeycloakTokenSupplier {

    String token(boolean forceRefresh);
}