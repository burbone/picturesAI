package com.picturesAI.api_gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class TierKeyResolver implements KeyResolver {

    private static final Logger log = LoggerFactory.getLogger(TierKeyResolver.class);
    private static final String TIER_PRO = "pro";

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(this::keyFor)
                .switchIfEmpty(Mono.defer(() -> fromSecurityContext(exchange)))
                .doOnNext(key -> log.debug("rate-limit key resolved: {}", key));
    }

    private Mono<String> fromSecurityContext(ServerWebExchange exchange) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(this::keyFor)
                .switchIfEmpty(Mono.fromSupplier(() -> anonymousKey(exchange)));
    }

    private String anonymousKey(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        String ip = (remote != null && remote.getAddress() != null)
                ? remote.getAddress().getHostAddress()
                : "unknown";
        return "anon:" + ip;
    }

    private String keyFor(JwtAuthenticationToken auth) {
        Jwt jwt = auth.getToken();
        String userId = jwt.getSubject();
        return userId + ":" + tierOf(auth);
    }

    private String tierOf(JwtAuthenticationToken auth) {
        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()).toLowerCase())
                .collect(Collectors.toSet());
        return roles.contains(TIER_PRO) ? TIER_PRO : "free";
    }
}