package com.picturesAI.user_service.security;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Profile("dev")
public class JwtCurrentUserProvider implements CurrentUserProvider {

    @Override
    public UUID userId() {
        Jwt jwt = currentJwt();
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new UnauthorizedException("JWT has no sub claim");
        }
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("JWT sub is not a UUID");
        }
    }

    @Override
    public Set<String> roles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Collections.emptySet();
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String username() {
        String raw = currentJwt().getClaimAsString("preferred_username");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw;
    }

    private Jwt currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return token.getToken();
        }
        throw new UnauthorizedException("No JWT authentication in context");
    }
}