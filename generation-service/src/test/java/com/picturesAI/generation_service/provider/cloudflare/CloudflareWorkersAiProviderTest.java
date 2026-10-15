package com.picturesAI.generation_service.provider.cloudflare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.picturesAI.generation_service.provider.GenerationRequest;
import com.picturesAI.generation_service.provider.ProviderException;
import com.picturesAI.generation_service.provider.ProviderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudflareWorkersAiProviderTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private CloudflareWorkersAiProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CloudflareWorkersAiProvider(props(0L), new ObjectMapper());
    }

    private CloudflareProperties props(long timeoutMs) {
        return new CloudflareProperties(
                "test-account",
                "test-token",
                wireMock.baseUrl(),
                "@cf/black-forest-labs/flux-1-schnell",
                4,
                timeoutMs <= 0 ? Duration.ofSeconds(5) : Duration.ofMillis(timeoutMs));
    }

    @Test
    void shouldDecodeBase64ImageOn200() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2, 3};
        String base64 = Base64.getEncoder().encodeToString(png);
        String responseBody = "{\"result\":{\"image\":\"" + base64 + "\"},\"success\":true,\"errors\":[],\"messages\":[]}";

        wireMock.stubFor(post(urlEqualTo("/accounts/test-account/ai/run/@cf/black-forest-labs/flux-1-schnell"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));

        GenerationRequest request = new GenerationRequest("a cat", null, "flux", "512x512");
        ProviderResponse response = provider.generate(request);

        assertThat(response.data()).isEqualTo(png);
        assertThat(response.contentType()).isEqualTo("image/png");
        assertThat(response.providerName()).isEqualTo("cloudflare-workers-ai");
        assertThat(response.providerJobId()).isNull();

        wireMock.verify(postRequestedFor(urlEqualTo("/accounts/test-account/ai/run/@cf/black-forest-labs/flux-1-schnell"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .withRequestBody(containing("\"prompt\":\"a cat\""))
                .withRequestBody(containing("\"steps\":4")));
    }

    @Test
    void shouldThrow401AsNotRetryable() {
        wireMock.stubFor(post(urlPathMatching("/accounts/.*"))
                .willReturn(aResponse().withStatus(401)));

        GenerationRequest request = new GenerationRequest("a cat", null, "flux", "512x512");

        assertThatThrownBy(() -> provider.generate(request))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    ProviderException pe = (ProviderException) e;
                    assertThat(pe.httpStatus()).isEqualTo(401);
                    assertThat(pe.retryable()).isFalse();
                });
    }

    @Test
    void shouldThrow403AsNotRetryable() {
        wireMock.stubFor(post(urlPathMatching("/accounts/.*"))
                .willReturn(aResponse().withStatus(403)));

        GenerationRequest request = new GenerationRequest("a cat", null, "flux", "512x512");

        assertThatThrownBy(() -> provider.generate(request))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    ProviderException pe = (ProviderException) e;
                    assertThat(pe.httpStatus()).isEqualTo(403);
                    assertThat(pe.retryable()).isFalse();
                });
    }

    @Test
    void shouldThrow429AsRetryable() {
        wireMock.stubFor(post(urlPathMatching("/accounts/.*"))
                .willReturn(aResponse().withStatus(429)));

        GenerationRequest request = new GenerationRequest("a cat", null, "flux", "512x512");

        assertThatThrownBy(() -> provider.generate(request))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    ProviderException pe = (ProviderException) e;
                    assertThat(pe.httpStatus()).isEqualTo(429);
                    assertThat(pe.retryable()).isTrue();
                });
    }

    @Test
    void shouldThrow500AsRetryable() {
        wireMock.stubFor(post(urlPathMatching("/accounts/.*"))
                .willReturn(aResponse().withStatus(500)));

        GenerationRequest request = new GenerationRequest("a cat", null, "flux", "512x512");

        assertThatThrownBy(() -> provider.generate(request))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    ProviderException pe = (ProviderException) e;
                    assertThat(pe.httpStatus()).isEqualTo(500);
                    assertThat(pe.retryable()).isTrue();
                });
    }

    @Test
    void shouldThrow503OnTimeout() {
        CloudflareWorkersAiProvider fastTimeoutProvider =
                new CloudflareWorkersAiProvider(props(100L), new ObjectMapper());

        wireMock.stubFor(post(urlPathMatching("/accounts/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(500)
                        .withBody("{}")));

        GenerationRequest request = new GenerationRequest("a cat", null, "flux", "512x512");

        assertThatThrownBy(() -> fastTimeoutProvider.generate(request))
                .isInstanceOf(ProviderException.class)
                .extracting("httpStatus")
                .isEqualTo(503);
    }

    @Test
    void shouldThrowWhenSuccessFalse() {
        wireMock.stubFor(post(urlPathMatching("/accounts/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"result\":null,\"success\":false,\"errors\":[{\"code\":7000,\"message\":\"no\"}],\"messages\":[]}")));

        GenerationRequest request = new GenerationRequest("a cat", null, "flux", "512x512");

        assertThatThrownBy(() -> provider.generate(request))
                .isInstanceOf(ProviderException.class)
                .extracting("httpStatus")
                .isEqualTo(502);
    }
}