package com.picturesAI.generation_service.security;

import java.util.Set;
import java.util.UUID;

public interface CurrentUserProvider {

    UUID userId();

    Set<String> roles();

    String username();
}