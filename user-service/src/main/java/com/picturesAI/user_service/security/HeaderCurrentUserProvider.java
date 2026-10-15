package com.picturesAI.user_service.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Profile("!dev")
public class HeaderCurrentUserProvider implements CurrentUserProvider {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLES_HEADER = "X-User-Roles";
    private static final String USER_NAME_HEADER = "X-User-Name";

    @Override
    public UUID userId() {
        String raw = header(USER_ID_HEADER);
        if (raw == null || raw.isBlank()) {
            throw new UnauthorizedException("Missing " + USER_ID_HEADER);
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid " + USER_ID_HEADER);
        }
    }

    @Override
    public Set<String> roles() {
        String raw = header(USER_ROLES_HEADER);
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String username() {
        String raw = header(USER_NAME_HEADER);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw;
    }

    private String header(String name) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        return request.getHeader(name);
    }
}