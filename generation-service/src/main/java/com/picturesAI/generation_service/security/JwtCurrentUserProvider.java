package com.picturesAI.generation_service.security;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Profile("dev")
public class JwtCurrentUserProvider implements CurrentUserProvider {

    @Override
    public UUID userId() {
        String sub = jwt().getSubject();
        return sub == null ? null : UUID.fromString(sub);
    }

    @Override
    public Set<String> roles() {
        Map<String, Object> realmAccess = jwt().getClaim("realm_access");
        if (realmAccess == null) {
            return Set.of();
        }
        Object roles = realmAccess.get("roles");
        if (!(roles instanceof Collection<?> collection)) {
            return Set.of();
        }
        return collection.stream()
                .map(Object::toString)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String username() {
        return jwt().getClaimAsString("preferred_username");
    }

    private Jwt jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No JWT authentication present in SecurityContext");
        }
        return jwt;
    }
}