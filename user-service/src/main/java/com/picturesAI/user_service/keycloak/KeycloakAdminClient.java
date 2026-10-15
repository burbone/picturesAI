package com.picturesAI.user_service.keycloak;

import java.util.UUID;

public interface KeycloakAdminClient {

    void deleteUser(UUID keycloakId);
}