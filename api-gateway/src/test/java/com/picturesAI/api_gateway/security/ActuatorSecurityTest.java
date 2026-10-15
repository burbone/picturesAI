package com.picturesAI.api_gateway.security;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ActuatorSecurityTest {

    static final String ISSUER = "http://test-issuer";
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
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:" + wireMock.port() + "/jwks");
    }

    @Autowired
    WebTestClient webTestClient;

    @Test
    void healthIsPublic() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void infoWithoutTokenReturns401() {
        webTestClient.get().uri("/actuator/info")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void infoWithUserTokenReturns403() throws Exception {
        String token = signToken("u1", List.of("user"));
        webTestClient.get().uri("/actuator/info")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void infoWithAdminTokenReturns200() throws Exception {
        String token = signToken("admin-1", List.of("admin"));
        webTestClient.get().uri("/actuator/info")
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