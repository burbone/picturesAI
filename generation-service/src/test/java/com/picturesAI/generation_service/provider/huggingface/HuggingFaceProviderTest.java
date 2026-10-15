package com.picturesAI.generation_service.provider.huggingface;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.picturesAI.generation_service.provider.GenerationRequest;
import com.picturesAI.generation_service.provider.ProviderException;
import com.picturesAI.generation_service.provider.ProviderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HuggingFaceProviderTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private HuggingFaceProvider provider;

    @BeforeEach
    void setUp() {
        provider = new HuggingFaceProvider(props(0L));
    }

    private HuggingFaceProperties props(long minIntervalMs) {
        return new HuggingFaceProperties(
                "test-key",
                wireMock.baseUrl(),
                Duration.ofSeconds(2),
                "black-forest-labs/FLUX.1-schnell",
                new HuggingFaceProperties.RateLimit(minIntervalMs));
    }

    @Test
    void shouldReturnImageOn200() {
        byte[] image = {9, 8, 7};
        wireMock.stubFor(post(urlEqualTo("/models/black-forest-labs/FLUX.1-schnell"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                        .withBody(image)));

        GenerationRequest request = new GenerationRequest("cat", null, "any", "512x512");
        ProviderResponse response = provider.generate(request);

        assertThat(response.data()).isEqualTo(image);
        assertThat(response.contentType()).isEqualTo(MediaType.IMAGE_JPEG_VALUE);
        assertThat(response.providerJobId()).isNull();
        assertThat(response.providerName()).isEqualTo("huggingface");

        wireMock.verify(postRequestedFor(urlEqualTo("/models/black-forest-labs/FLUX.1-schnell"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer test-key"))
                .withHeader(HttpHeaders.CONTENT_TYPE, containing("application/json"))
                .withRequestBody(containing("\"inputs\""))
                .withRequestBody(containing("cat")));
    }

    @Test
    void shouldThrow429OnRateLimit() {
        wireMock.stubFor(post(urlPathMatching("/models/.*"))
                .willReturn(aResponse().withStatus(429)));

        GenerationRequest request = new GenerationRequest("cat", null, null, "512x512");

        assertThatThrownBy(() -> provider.generate(request))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    ProviderException pe = (ProviderException) e;
                    assertThat(pe.httpStatus()).isEqualTo(429);
                    assertThat(pe.retryable()).isTrue();
                });

        wireMock.verify(1, postRequestedFor(urlPathMatching("/models/.*")));
    }

    @Test
    void shouldThrow500OnServerError() {
        wireMock.stubFor(post(urlPathMatching("/models/.*"))
                .willReturn(aResponse().withStatus(500)));

        GenerationRequest request = new GenerationRequest("cat", null, null, "512x512");

        assertThatThrownBy(() -> provider.generate(request))
                .isInstanceOf(ProviderException.class)
                .extracting("httpStatus")
                .isEqualTo(500);
    }

    @Test
    void shouldThrow503OnTimeout() {
        HuggingFaceProvider fastTimeoutProvider = new HuggingFaceProvider(
                new HuggingFaceProperties(
                        "test-key",
                        wireMock.baseUrl(),
                        Duration.ofMillis(100),
                        "black-forest-labs/FLUX.1-schnell",
                        new HuggingFaceProperties.RateLimit(0L)));

        wireMock.stubFor(post(urlPathMatching("/models/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(500)
                        .withBody(new byte[]{1})));

        GenerationRequest request = new GenerationRequest("cat", null, null, "512x512");

        assertThatThrownBy(() -> fastTimeoutProvider.generate(request))
                .isInstanceOf(ProviderException.class)
                .extracting("httpStatus")
                .isEqualTo(503);
    }

    @Test
    void shouldThrow401AsNotRetryable() {
        wireMock.stubFor(post(urlPathMatching("/models/.*"))
                .willReturn(aResponse().withStatus(401)));

        GenerationRequest request = new GenerationRequest("cat", null, null, "512x512");

        assertThatThrownBy(() -> provider.generate(request))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    ProviderException pe = (ProviderException) e;
                    assertThat(pe.httpStatus()).isEqualTo(401);
                    assertThat(pe.retryable()).isFalse();
                });
    }

    @Test
    void pausesBetweenConsecutiveRequests() {
        wireMock.stubFor(post(urlPathMatching("/models/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                        .withBody(new byte[]{4})));

        HuggingFaceProvider p = new HuggingFaceProvider(props(100L));
        GenerationRequest request = new GenerationRequest("cat", null, null, "512x512");

        long start = System.currentTimeMillis();
        p.generate(request);
        p.generate(request);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(100L);
        wireMock.verify(2, postRequestedFor(urlPathMatching("/models/.*")));
    }

    @Test
    void doesNotPauseWhenIntervalZero() {
        wireMock.stubFor(post(urlPathMatching("/models/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                        .withBody(new byte[]{4})));

        HuggingFaceProvider p = new HuggingFaceProvider(props(0L));
        GenerationRequest request = new GenerationRequest("cat", null, null, "512x512");

        long start = System.currentTimeMillis();
        p.generate(request);
        p.generate(request);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(100L);
    }
}