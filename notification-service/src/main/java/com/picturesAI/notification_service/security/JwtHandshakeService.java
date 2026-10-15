package com.picturesAI.notification_service.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class JwtHandshakeService {

    private static final Pattern GENERATION_PATH = Pattern.compile("^/ws/generations/([^/]+)$");
    private static final String NOTIFICATIONS_PATH = "/ws/notifications";
    private static final String TOKEN_PARAM = "token";

    private final JwtDecoder jwtDecoder;

    public JwtHandshakeService(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    public Mono<HandshakeResult> authenticate(URI uri) {
        String path = uri.getPath();
        boolean isUser = NOTIFICATIONS_PATH.equals(path);
        Matcher generationMatcher = GENERATION_PATH.matcher(path);
        boolean isGeneration = generationMatcher.matches();

        if (!isUser && !isGeneration) {
            return Mono.just(HandshakeResult.unauthorized());
        }

        UUID generationId = null;
        if (isGeneration) {
            try {
                generationId = UUID.fromString(generationMatcher.group(1));
            } catch (IllegalArgumentException e) {
                return Mono.just(HandshakeResult.unauthorized());
            }
        }

        String token = extractToken(uri.getQuery());
        if (token == null || token.isBlank()) {
            return Mono.just(HandshakeResult.unauthorized());
        }

        Jwt jwt;
        UUID userId;
        String username;
        Set<String> roles;
        try {
            jwt = jwtDecoder.decode(token);
            userId = UUID.fromString(jwt.getSubject());
            username = jwt.getClaimAsString("preferred_username");
            roles = extractRealmRoles(jwt);
        } catch (JwtException | IllegalArgumentException e) {
            return Mono.just(HandshakeResult.unauthorized());
        }

        if (isUser) {
            return Mono.just(HandshakeResult.user(userId, username, roles));
        }
        return Mono.just(HandshakeResult.generation(userId, username, roles, generationId));
    }

    private String extractToken(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String part : query.split("&")) {
            int idx = part.indexOf('=');
            if (idx > 0 && TOKEN_PARAM.equals(part.substring(0, idx))) {
                return part.substring(idx + 1);
            }
        }
        return null;
    }

    private Set<String> extractRealmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (!(realmAccess instanceof Map<?, ?> map)) {
            return Collections.emptySet();
        }
        Object roles = map.get("roles");
        if (!(roles instanceof List<?> list)) {
            return Collections.emptySet();
        }
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toUnmodifiableSet());
    }
}