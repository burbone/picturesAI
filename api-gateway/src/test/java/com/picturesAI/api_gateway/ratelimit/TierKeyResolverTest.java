package com.picturesAI.api_gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TierKeyResolverTest {

    private final TierKeyResolver resolver = new TierKeyResolver();

    @Test
    void proUserGetsProKey() {
        JwtAuthenticationToken auth = auth("user-1",
                new SimpleGrantedAuthority("ROLE_user"),
                new SimpleGrantedAuthority("ROLE_pro"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/generations").build());

        String key = resolver.resolve(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        assertThat(key).isEqualTo("user-1:pro");
    }

    @Test
    void freeUserGetsFreeKey() {
        JwtAuthenticationToken auth = auth("user-2",
                new SimpleGrantedAuthority("ROLE_user"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/generations").build());

        String key = resolver.resolve(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();

        assertThat(key).isEqualTo("user-2:free");
    }

    @Test
    void principalOnExchangeWins() {
        JwtAuthenticationToken auth = auth("user-3",
                new SimpleGrantedAuthority("ROLE_user"));
        ServerWebExchange exchange = MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/v1/generations").build())
                .mutate().principal(Mono.just(auth)).build();

        String key = resolver.resolve(exchange).block();

        assertThat(key).isEqualTo("user-3:free");
    }

    @Test
    void anonymousGetsIpBasedKey() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/feed")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        String key = resolver.resolve(exchange).block();

        assertThat(key).isEqualTo("anon:10.0.0.5");
    }

    @Test
    void differentUsersGetDifferentKeys() {
        JwtAuthenticationToken auth1 = auth("alice", new SimpleGrantedAuthority("ROLE_user"));
        JwtAuthenticationToken auth2 = auth("bob", new SimpleGrantedAuthority("ROLE_user"));
        MockServerWebExchange ex1 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/generations").build());
        MockServerWebExchange ex2 = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/generations").build());

        String key1 = resolver.resolve(ex1)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth1)).block();
        String key2 = resolver.resolve(ex2)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth2)).block();

        assertThat(key1).isNotEqualTo(key2);
    }

    private JwtAuthenticationToken auth(String sub, SimpleGrantedAuthority... authorities) {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(sub)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(authorities), sub);
    }
}