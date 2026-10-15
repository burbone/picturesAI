package com.picturesAI.generation_service.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Profile("!dev")
@RequestScope
public class HeaderCurrentUserProvider implements CurrentUserProvider {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USER_ROLES = "X-User-Roles";
    private static final String HEADER_USER_NAME = "X-User-Name";

    private final HttpServletRequest request;

    public HeaderCurrentUserProvider(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public UUID userId() {
        String value = request.getHeader(HEADER_USER_ID);
        if (value == null || value.isBlank()) {
            return null;
        }
        return UUID.fromString(value);
    }

    @Override
    public Set<String> roles() {
        String value = request.getHeader(HEADER_USER_ROLES);
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String username() {
        return request.getHeader(HEADER_USER_NAME);
    }
}