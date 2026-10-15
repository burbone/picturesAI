package com.picturesAI.generation_service.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderFallbackChainTest {

    private static final GenerationRequest REQ =
            new GenerationRequest("cat", null, "flux", "512x512");

    @Test
    void returnsFirstOnSuccess() {
        ProviderResponse first = new ProviderResponse(new byte[]{1}, "image/jpeg", null, "a");
        ImageProvider a = mock(ImageProvider.class);
        ImageProvider b = mock(ImageProvider.class);
        when(a.name()).thenReturn("a");
        when(b.name()).thenReturn("b");
        when(a.generate(REQ)).thenReturn(first);

        ProviderFallbackChain chain = new ProviderFallbackChain(List.of(a, b));

        assertThat(chain.generate(REQ)).isEqualTo(first);
        verify(b, never()).generate(REQ);
    }

    @Test
    void fallsBackToSecondOnFailure() {
        ProviderResponse second = new ProviderResponse(new byte[]{2}, "image/jpeg", null, "b");
        ImageProvider a = mock(ImageProvider.class);
        ImageProvider b = mock(ImageProvider.class);
        when(a.name()).thenReturn("a");
        when(b.name()).thenReturn("b");
        when(a.generate(REQ)).thenThrow(new ProviderException(500, "boom"));
        when(b.generate(REQ)).thenReturn(second);

        ProviderFallbackChain chain = new ProviderFallbackChain(List.of(a, b));

        assertThat(chain.generate(REQ)).isEqualTo(second);
    }

    @Test
    void rethrowsLastWhenAllFail() {
        ImageProvider a = mock(ImageProvider.class);
        ImageProvider b = mock(ImageProvider.class);
        when(a.name()).thenReturn("a");
        when(b.name()).thenReturn("b");
        when(a.generate(REQ)).thenThrow(new ProviderException(500, "first failure"));
        when(b.generate(REQ)).thenThrow(new ProviderException(503, "last failure"));

        ProviderFallbackChain chain = new ProviderFallbackChain(List.of(a, b));

        assertThatThrownBy(() -> chain.generate(REQ))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("last failure")
                .extracting("httpStatus")
                .isEqualTo(503);
    }

    @Test
    void throwsWhenNoProviders() {
        assertThatThrownBy(() -> new ProviderFallbackChain(List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void filtersSelfFromList() {
        ProviderResponse r = new ProviderResponse(new byte[]{1}, "image/jpeg", null, "a");
        ImageProvider a = mock(ImageProvider.class);
        when(a.name()).thenReturn("a");
        when(a.generate(REQ)).thenReturn(r);

        ProviderFallbackChain chain = new ProviderFallbackChain(List.of(a, chainHolder()));
        assertThat(chain.generate(REQ)).isEqualTo(r);
    }

    private static ProviderFallbackChain chainHolder() {
        ImageProvider noop = mock(ImageProvider.class);
        when(noop.name()).thenReturn("noop");
        return new ProviderFallbackChain(List.of(noop));
    }
}