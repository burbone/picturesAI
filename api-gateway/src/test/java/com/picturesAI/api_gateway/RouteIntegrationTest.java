package com.picturesAI.api_gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RouteIntegrationTest {

    static final String ISSUER = "http://test-issuer";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static WireMockServer wireMock;
    static RSAKey rsaKey;

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        rsaKey = new RSAKeyGenerator(2048).keyID("test-kid").generate();
        JWKSet jwkSet = new JWKSet(rsaKey.toPublicJWK());
        wireMock.stubFor(get(urlEqualTo("/jwks"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(jwkSet.toString())));
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:" + wireMock.port() + "/jwks");
        registry.add("app.downstream.user-service",
                () -> "http://localhost:" + wireMock.port());
    }

    @Autowired
    WebTestClient webTestClient;

    @BeforeEach
    void reset() {
        wireMock.resetRequests();
    }

    @Test
    void routesUsersMeToUserServiceWithInjectedHeaders() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/api/v1/users/me"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        String token = signToken("user-42", "alice", List.of("user"));

        webTestClient.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", "hacker")
                .header("X-User-Name", "hacker-name")
                .header("X-User-Roles", "admin")
                .header("X-Request-Id", "req-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ok");

        wireMock.verify(getRequestedFor(urlEqualTo("/api/v1/users/me"))
                .withHeader("X-User-Id", equalTo("user-42"))
                .withHeader("X-User-Name", equalTo("alice"))
                .withHeader("X-User-Roles", equalTo("user"))
                .withHeader("X-Request-Id", equalTo("req-1"))
                .withHeader("X-Trace-Id", matching(".+")));
    }

    @Test
    void usersMeWithoutTokenReturns401() {
        webTestClient.get().uri("/api/v1/users/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void usersMeWithMalformedTokenReturns401() {
        webTestClient.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private String signToken(String subject, String username, List<String> roles) throws Exception {
        JWSSigner signer = new RSASSASigner(rsaKey);
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .claim("realm_access", Map.of("roles", roles));
        if (username != null) {
            builder.claim("preferred_username", username);
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                builder.build());
        jwt.sign(signer);
        return jwt.serialize();
    }
}