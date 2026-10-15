package com.picturesAI.api_gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CircuitBreakerIntegrationTest {

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
        registry.add("app.downstream.billing-service",
                () -> "http://localhost:" + wireMock.port());
        registry.add("app.rate-limit.billing", () -> "1000");
    }

    @Autowired
    WebTestClient webTestClient;

    @BeforeEach
    void reset() {
        wireMock.resetAll();
        wireMock.stubFor(get(urlEqualTo("/jwks"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(new JWKSet(rsaKey.toPublicJWK()).toString())));
    }

    @Test
    void circuitOpensAfterFailuresThenClosesAfterWait() throws Exception {
        String token = signToken("user-1", List.of("user"));

        wireMock.stubFor(get(urlEqualTo("/api/v1/billing/status"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        for (int i = 0; i < 6; i++) {
            webTestClient.get().uri("/api/v1/billing/status")
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus().is5xxServerError();
        }

        webTestClient.get().uri("/api/v1/billing/status")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(503);

        Thread.sleep(2500);

        wireMock.stubFor(get(urlEqualTo("/api/v1/billing/status"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        webTestClient.get().uri("/api/v1/billing/status")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        webTestClient.get().uri("/api/v1/billing/status")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
    }

    private String signToken(String subject, List<String> roles) throws Exception {
        JWSSigner signer = new RSASSASigner(rsaKey);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .claim("realm_access", Map.of("roles", roles))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                claims);
        jwt.sign(signer);
        return jwt.serialize();
    }
}