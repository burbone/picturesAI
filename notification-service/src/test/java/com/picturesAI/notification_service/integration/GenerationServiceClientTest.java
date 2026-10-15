package com.picturesAI.notification_service.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class GenerationServiceClientTest {

    private WireMockServer wireMock;
    private GenerationServiceClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        client = new GenerationServiceClient(wireMock.baseUrl(), 500);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void returnsOkWithPayloadOn200() {
        UUID gid = UUID.randomUUID();
        wireMock.stubFor(get(urlPathMatching("/api/v1/generations/.*/status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + gid + "\",\"status\":\"COMPLETED\",\"tier\":\"FREE\"}")));

        StepVerifier.create(client.fetchStatus(gid, UUID.randomUUID(), Set.of("user"), "alice"))
                .assertNext(result -> {
                    assertThat(result.access()).isEqualTo(GenerationAccessResult.OK);
                    assertThat(result.payload()).isNotNull();
                    assertThat(result.payload().get("status").asText()).isEqualTo("COMPLETED");
                })
                .verifyComplete();
    }

    @Test
    void returnsOkWithNullPayloadOnEmpty200() {
        UUID gid = UUID.randomUUID();
        wireMock.stubFor(get(urlPathMatching("/api/v1/generations/.*/status"))
                .willReturn(aResponse().withStatus(200)));

        StepVerifier.create(client.fetchStatus(gid, UUID.randomUUID(), Set.of("user"), "alice"))
                .assertNext(result -> {
                    assertThat(result.access()).isEqualTo(GenerationAccessResult.OK);
                    assertThat(result.payload()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void returnsNotFoundOn404() {
        UUID gid = UUID.randomUUID();
        wireMock.stubFor(get(urlPathMatching("/api/v1/generations/.*/status"))
                .willReturn(aResponse().withStatus(404)));

        StepVerifier.create(client.fetchStatus(gid, UUID.randomUUID(), Set.of("user"), "alice"))
                .assertNext(result -> {
                    assertThat(result.access()).isEqualTo(GenerationAccessResult.NOT_FOUND);
                    assertThat(result.payload()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void returnsForbiddenOn403() {
        UUID gid = UUID.randomUUID();
        wireMock.stubFor(get(urlPathMatching("/api/v1/generations/.*/status"))
                .willReturn(aResponse().withStatus(403)));

        StepVerifier.create(client.fetchStatus(gid, UUID.randomUUID(), Set.of("user"), "alice"))
                .assertNext(result -> {
                    assertThat(result.access()).isEqualTo(GenerationAccessResult.FORBIDDEN);
                    assertThat(result.payload()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void returnsInternalErrorOn500() {
        UUID gid = UUID.randomUUID();
        wireMock.stubFor(get(urlPathMatching("/api/v1/generations/.*/status"))
                .willReturn(aResponse().withStatus(500)));

        StepVerifier.create(client.fetchStatus(gid, UUID.randomUUID(), Set.of("user"), "alice"))
                .assertNext(result -> assertThat(result.access()).isEqualTo(GenerationAccessResult.INTERNAL_ERROR))
                .verifyComplete();
    }

    @Test
    void returnsInternalErrorOn503() {
        UUID gid = UUID.randomUUID();
        wireMock.stubFor(get(urlPathMatching("/api/v1/generations/.*/status"))
                .willReturn(aResponse().withStatus(503)));

        StepVerifier.create(client.fetchStatus(gid, UUID.randomUUID(), Set.of("user"), "alice"))
                .assertNext(result -> assertThat(result.access()).isEqualTo(GenerationAccessResult.INTERNAL_ERROR))
                .verifyComplete();
    }

    @Test
    void returnsInternalErrorOnTimeout() {
        UUID gid = UUID.randomUUID();
        wireMock.stubFor(get(urlPathMatching("/api/v1/generations/.*/status"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2000)));

        StepVerifier.create(client.fetchStatus(gid, UUID.randomUUID(), Set.of("user"), "alice"))
                .assertNext(result -> assertThat(result.access()).isEqualTo(GenerationAccessResult.INTERNAL_ERROR))
                .verifyComplete();
    }

    @Test
    void returnsInternalErrorWhenServiceUnreachable() {
        wireMock.stop();
        UUID gid = UUID.randomUUID();

        StepVerifier.create(client.fetchStatus(gid, UUID.randomUUID(), Set.of("user"), "alice"))
                .assertNext(result -> assertThat(result.access()).isEqualTo(GenerationAccessResult.INTERNAL_ERROR))
                .verifyComplete();
    }

    @Test
    void forwardsUserHeaders() {
        UUID gid = UUID.randomUUID();
        UUID uid = UUID.randomUUID();
        wireMock.stubFor(get(urlPathMatching("/api/v1/generations/.*/status"))
                .willReturn(aResponse().withStatus(200)));

        client.fetchStatus(gid, uid, Set.of("user", "admin"), "alice").block();

        wireMock.verify(getRequestedFor(urlPathMatching("/api/v1/generations/" + gid + "/status"))
                .withHeader("X-User-Id", equalTo(uid.toString()))
                .withHeader("X-User-Roles", equalTo("admin,user"))
                .withHeader("X-User-Name", equalTo("alice")));
    }

    @Test
    void handlesEmptyRoles() {
        UUID gid = UUID.randomUUID();
        wireMock.stubFor(get(urlPathMatching("/api/v1/generations/.*/status"))
                .willReturn(aResponse().withStatus(200)));

        StepVerifier.create(client.fetchStatus(gid, UUID.randomUUID(), Set.of(), "alice"))
                .assertNext(result -> assertThat(result.access()).isEqualTo(GenerationAccessResult.OK))
                .verifyComplete();

        wireMock.verify(getRequestedFor(urlPathMatching("/api/v1/generations/.*/status"))
                .withHeader("X-User-Roles", equalTo("")));
    }
}