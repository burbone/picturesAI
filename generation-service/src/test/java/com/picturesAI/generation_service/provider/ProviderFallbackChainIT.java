package com.picturesAI.generation_service.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.picturesAI.generation_service.provider.cloudflare.CloudflareProperties;
import com.picturesAI.generation_service.provider.huggingface.HuggingFaceProperties;
import com.picturesAI.generation_service.provider.huggingface.HuggingFaceProvider;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Base64;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderFallbackChainIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("demo"))
            .withUserConfiguration(TestConfig.class);

    private static final GenerationRequest REQ =
            new GenerationRequest("cat", null, "flux", "512x512");

    @Test
    void fallbackChainIsFirstInProviderList() {
        withBothProviders().run(ctx -> {
            Map<String, ImageProvider> beans = ctx.getBeansOfType(ImageProvider.class);
            ImageProvider first = beans.values().stream()
                    .min(java.util.Comparator.comparingInt(p -> orderOf(ctx, p)))
                    .orElseThrow();
            assertThat(first).isInstanceOf(ProviderFallbackChain.class);
        });
    }

    @Test
    void cloudflareSucceedsNoFallback() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2, 3};
        String base64 = Base64.getEncoder().encodeToString(png);
        String cfBody = "{\"result\":{\"image\":\"" + base64 + "\"},\"success\":true}";

        wireMock.stubFor(post(urlPathMatching("/accounts/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                        .withBody(cfBody)));

        withBothProviders().run(ctx -> {
            assertThat(ctx).hasSingleBean(ProviderFallbackChain.class);
            ImageProvider provider = ctx.getBean(ImageProvider.class);
            assertThat(provider).isInstanceOf(ProviderFallbackChain.class);

            ProviderResponse resp = provider.generate(REQ);
            assertThat(resp.providerName()).isEqualTo("cloudflare-workers-ai");

            wireMock.verify(0, postRequestedFor(urlPathMatching("/models/.*")));
        });
    }

    @Test
    void cloudflareFailureFallsBackToHuggingFace() {
        wireMock.stubFor(post(urlPathMatching("/accounts/.*"))
                .willReturn(aResponse().withStatus(500)));
        wireMock.stubFor(post(urlPathMatching("/models/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                        .withBody(new byte[]{9, 9, 9})));

        withBothProviders().run(ctx -> {
            ImageProvider provider = ctx.getBean(ImageProvider.class);
            ProviderResponse resp = provider.generate(REQ);
            assertThat(resp.data()).isEqualTo(new byte[]{9, 9, 9});
            assertThat(resp.providerName()).isEqualTo("huggingface");

            wireMock.verify(1, postRequestedFor(urlPathMatching("/accounts/.*")));
            wireMock.verify(1, postRequestedFor(urlPathMatching("/models/.*")));
        });
    }

    @Test
    void noHuggingFaceKeyOnlyCloudflare() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 1};
        String base64 = Base64.getEncoder().encodeToString(png);
        String cfBody = "{\"result\":{\"image\":\"" + base64 + "\"},\"success\":true}";

        wireMock.stubFor(post(urlPathMatching("/accounts/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                        .withBody(cfBody)));

        runner.withPropertyValues(
                        "cloudflare.base-url=" + wireMock.baseUrl(),
                        "cloudflare.account-id=test",
                        "cloudflare.api-token=test-token")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(HuggingFaceProvider.class);
                    ImageProvider provider = ctx.getBean(ImageProvider.class);
                    provider.generate(REQ);
                    wireMock.verify(0, postRequestedFor(urlPathMatching("/models/.*")));
                });
    }

    @Test
    void bothFailRethrowsLast() {
        wireMock.stubFor(post(urlPathMatching("/accounts/.*"))
                .willReturn(aResponse().withStatus(500)));
        wireMock.stubFor(post(urlPathMatching("/models/.*"))
                .willReturn(aResponse().withStatus(503)));

        withBothProviders().run(ctx -> {
            ImageProvider provider = ctx.getBean(ImageProvider.class);
            assertThatThrownBy(() -> provider.generate(REQ))
                    .isInstanceOf(ProviderException.class)
                    .extracting("httpStatus")
                    .isEqualTo(503);
        });
    }

    private ApplicationContextRunner withBothProviders() {
        return runner.withPropertyValues(
                "cloudflare.base-url=" + wireMock.baseUrl(),
                "cloudflare.account-id=test",
                "cloudflare.api-token=test-token",
                "huggingface.api-key=test-key",
                "huggingface.base-url=" + wireMock.baseUrl()
        );
    }

    private static int orderOf(org.springframework.context.ApplicationContext ctx, ImageProvider p) {
        Class<?> type = p.getClass();
        org.springframework.core.annotation.Order order =
                org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                        type, org.springframework.core.annotation.Order.class);
        return order == null ? Integer.MAX_VALUE : order.value();
    }

    @Configuration
    @ComponentScan(basePackages = "com.picturesAI.generation_service.provider")
    @EnableConfigurationProperties({CloudflareProperties.class, HuggingFaceProperties.class})
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        CircuitBreakerRegistry circuitBreakerRegistry() {
            return CircuitBreakerRegistry.ofDefaults();
        }

        @Bean
        BulkheadRegistry bulkheadRegistry() {
            return BulkheadRegistry.ofDefaults();
        }

        @Bean
        RetryRegistry retryRegistry() {
            return RetryRegistry.of(RetryConfig.custom()
                    .maxAttempts(1)
                    .build());
        }
    }
}