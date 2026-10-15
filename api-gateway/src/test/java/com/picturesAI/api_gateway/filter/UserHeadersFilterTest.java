package com.picturesAI.api_gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UserHeadersFilterTest {

    private final UserHeadersFilter filter = new UserHeadersFilter();

    @Test
    void stripsClientUserHeadersAndSetsOwn() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("user-42")
                .claim("preferred_username", "alice")
                .claim("realm_access", Map.of("roles", List.of("user", "pro")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("ROLE_user"), new SimpleGrantedAuthority("ROLE_pro")),
                "user-42");

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/users/me")
                .header(UserHeadersFilter.HEADER_USER_ID, "hacker")
                .header(UserHeadersFilter.HEADER_USER_NAME, "hacker-name")
                .header(UserHeadersFilter.HEADER_USER_ROLES, "admin")
                .header("X-User-Anything", "evil")
                .header(UserHeadersFilter.HEADER_REQUEST_ID, "req-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        HttpHeaders headers = captured.get().getRequest().getHeaders();
        assertThat(headers.getFirst(UserHeadersFilter.HEADER_USER_ID)).isEqualTo("user-42");
        assertThat(headers.getFirst(UserHeadersFilter.HEADER_USER_NAME)).isEqualTo("alice");
        assertThat(headers.getFirst(UserHeadersFilter.HEADER_USER_ROLES)).isEqualTo("user,pro");
        assertThat(headers.getFirst("X-User-Anything")).isNull();
        assertThat(headers.getFirst(UserHeadersFilter.HEADER_REQUEST_ID)).isEqualTo("req-1");
        assertThat(headers.getFirst(UserHeadersFilter.HEADER_TRACE_ID)).isNotBlank();
    }

    @Test
    void omitsUserNameWhenClaimMissing() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("user-42")
                .claim("realm_access", Map.of("roles", List.of("user")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_user")), "user-42");

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/users/me")
                .header(UserHeadersFilter.HEADER_USER_NAME, "hacker-name")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        HttpHeaders headers = captured.get().getRequest().getHeaders();
        assertThat(headers.getFirst(UserHeadersFilter.HEADER_USER_ID)).isEqualTo("user-42");
        assertThat(headers.getFirst(UserHeadersFilter.HEADER_USER_NAME)).isNull();
    }

    @Test
    void omitsUserNameWhenClaimBlank() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("user-42")
                .claim("preferred_username", "   ")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_user")), "user-42");

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me").build());

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        assertThat(captured.get().getRequest().getHeaders()
                .getFirst(UserHeadersFilter.HEADER_USER_NAME)).isNull();
    }

    @Test
    void generatesRequestIdWhenMissing() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/feed").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        String requestId = captured.get().getRequest().getHeaders()
                .getFirst(UserHeadersFilter.HEADER_REQUEST_ID);
        assertThat(requestId).isNotBlank();
    }

    @Test
    void anonymousRequestGetsTraceIdButNoUserHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/feed")
                .header(UserHeadersFilter.HEADER_USER_ID, "hacker")
                .header(UserHeadersFilter.HEADER_USER_NAME, "hacker-name")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        HttpHeaders headers = captured.get().getRequest().getHeaders();
        assertThat(headers.getFirst(UserHeadersFilter.HEADER_USER_ID)).isNull();
        assertThat(headers.getFirst(UserHeadersFilter.HEADER_USER_NAME)).isNull();
        assertThat(headers.getFirst(UserHeadersFilter.HEADER_USER_ROLES)).isNull();
        assertThat(headers.getFirst(UserHeadersFilter.HEADER_TRACE_ID)).isNotBlank();
    }
}