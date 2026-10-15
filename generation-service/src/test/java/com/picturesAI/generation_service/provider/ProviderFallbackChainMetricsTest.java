package com.picturesAI.generation_service.provider;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderFallbackChainMetricsTest {

    private static final GenerationRequest REQ =
            new GenerationRequest("cat", null, "flux", "512x512");

    @Test
    void countsSuccess() {
        ImageProvider a = stub("a");
        when(a.generate(REQ)).thenReturn(
                new ProviderResponse(new byte[]{1}, "image/png", null, "a"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ProviderFallbackChain(List.of(a), registry).generate(REQ);

        assertThat(registry.counter("provider_calls_total",
                "provider", "a", "result", "success").count()).isEqualTo(1.0);
        assertThat(registry.find("provider_duration_seconds").tag("provider", "a").timer()).isNotNull();
        assertThat(registry.find("provider_concurrent").tag("provider", "a").gauge().value()).isZero();
    }

    @Test
    void countsError() {
        ImageProvider a = stub("a");
        when(a.generate(REQ)).thenThrow(new ProviderException(500, "boom"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThatThrownBy(() -> new ProviderFallbackChain(List.of(a), registry).generate(REQ))
                .isInstanceOf(ProviderException.class);

        assertThat(registry.counter("provider_calls_total",
                "provider", "a", "result", "error").count()).isEqualTo(1.0);
    }

    @Test
    void countsRateLimited() {
        ImageProvider a = stub("a");
        when(a.generate(REQ)).thenThrow(new ProviderException(429, "slow down"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThatThrownBy(() -> new ProviderFallbackChain(List.of(a), registry).generate(REQ))
                .isInstanceOf(ProviderException.class);

        assertThat(registry.counter("provider_calls_total",
                "provider", "a", "result", "rate_limited").count()).isEqualTo(1.0);
        assertThat(registry.counter("provider_rate_limited_total", "provider", "a").count())
                .isEqualTo(1.0);
    }

    @Test
    void countsKeyError() {
        ImageProvider a = stub("a");
        when(a.generate(REQ)).thenThrow(new ProviderException(401, "bad key"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThatThrownBy(() -> new ProviderFallbackChain(List.of(a), registry).generate(REQ))
                .isInstanceOf(ProviderException.class);

        assertThat(registry.counter("provider_calls_total",
                "provider", "a", "result", "key_error").count()).isEqualTo(1.0);
        assertThat(registry.counter("provider_key_errors_total", "provider", "a").count())
                .isEqualTo(1.0);
    }

    @Test
    void fallbackCountsBothProviders() {
        ImageProvider a = stub("a");
        ImageProvider b = stub("b");
        when(a.generate(REQ)).thenThrow(new ProviderException(500, "boom"));
        when(b.generate(REQ)).thenReturn(
                new ProviderResponse(new byte[]{2}, "image/png", null, "b"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ProviderFallbackChain(List.of(a, b), registry).generate(REQ);

        assertThat(registry.counter("provider_calls_total",
                "provider", "a", "result", "error").count()).isEqualTo(1.0);
        assertThat(registry.counter("provider_calls_total",
                "provider", "b", "result", "success").count()).isEqualTo(1.0);
    }

    private static ImageProvider stub(String name) {
        ImageProvider p = mock(ImageProvider.class);
        when(p.name()).thenReturn(name);
        return p;
    }
}