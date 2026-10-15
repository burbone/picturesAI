package com.picturesAI.api_gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class UserHeadersFilter implements GlobalFilter, Ordered {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_NAME = "X-User-Name";
    public static final String HEADER_USER_ROLES = "X-User-Roles";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    private static final List<String> CLIENT_MANAGED_PREFIXES = List.of("X-User-");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders().getFirst(HEADER_REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        String traceId = exchange.getRequest().getHeaders().getFirst(HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        String finalRequestId = requestId;
        String finalTraceId = traceId;

        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(auth -> decorate(exchange, auth, finalRequestId, finalTraceId))
                .defaultIfEmpty(decorateAnonymous(exchange, finalRequestId, finalTraceId))
                .flatMap(chain::filter);
    }

    private ServerWebExchange decorate(ServerWebExchange exchange, JwtAuthenticationToken auth,
                                       String requestId, String traceId) {
        Jwt jwt = auth.getToken();
        String userId = jwt.getSubject();
        String roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .collect(Collectors.joining(","));

        ServerHttpRequest.Builder builder = strip(exchange.getRequest())
                .header(HEADER_USER_ID, userId)
                .header(HEADER_USER_ROLES, roles)
                .header(HEADER_REQUEST_ID, requestId)
                .header(HEADER_TRACE_ID, traceId);

        String userName = jwt.getClaimAsString("preferred_username");
        if (userName != null && !userName.isBlank()) {
            builder.header(HEADER_USER_NAME, userName);
        }

        return exchange.mutate().request(builder.build()).build();
    }

    private ServerWebExchange decorateAnonymous(ServerWebExchange exchange,
                                                String requestId, String traceId) {
        ServerHttpRequest mutated = strip(exchange.getRequest())
                .header(HEADER_REQUEST_ID, requestId)
                .header(HEADER_TRACE_ID, traceId)
                .build();

        return exchange.mutate().request(mutated).build();
    }

    private ServerHttpRequest.Builder strip(ServerHttpRequest request) {
        ServerHttpRequest.Builder builder = request.mutate();
        request.getHeaders().keySet().stream()
                .filter(name -> CLIENT_MANAGED_PREFIXES.stream()
                        .anyMatch(prefix -> name.regionMatches(true, 0, prefix, 0, prefix.length())))
                .forEach(name -> builder.headers(h -> h.remove(name)));
        return builder;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}