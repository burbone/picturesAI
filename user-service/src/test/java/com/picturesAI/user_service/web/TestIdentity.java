package com.picturesAI.user_service.web;

import com.picturesAI.user_service.security.CurrentUserProvider;

import java.util.Set;
import java.util.UUID;

public class TestIdentity implements CurrentUserProvider {

    private UUID userId = UUID.randomUUID();
    private Set<String> roles = Set.of("ROLE_USER");
    private String username = "alice";

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @Override
    public UUID userId() {
        return userId;
    }

    @Override
    public Set<String> roles() {
        return roles;
    }

    @Override
    public String username() {
        return username;
    }
}
