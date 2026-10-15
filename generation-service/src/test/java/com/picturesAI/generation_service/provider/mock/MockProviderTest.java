package com.picturesAI.generation_service.provider.mock;

import com.picturesAI.generation_service.provider.GenerationRequest;
import com.picturesAI.generation_service.provider.ProviderException;
import com.picturesAI.generation_service.provider.ProviderResponse;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockProviderTest {

    private static final GenerationRequest ANY =
            new GenerationRequest("qwerty zxcvb", null, "sd-xl", "512x512");

    private MockProvider provider(MockProviderProperties props, long seed) {
        return new MockProvider(props, new Random(seed));
    }

    private MockProviderProperties defaults() {
        return new MockProviderProperties(0, 0, 0.0, 0.0);
    }

    @Test
    void keywordCatReturnsCatImage() {
        MockProvider p = provider(defaults(), 1);
        ProviderResponse resp = p.generate(
                new GenerationRequest("a cute cat on the sofa", null, "sd-xl", "512x512"));

        assertThat(resp.data()).isEqualTo(MockProvider.imageForSeed("cat"));
        assertThat(resp.contentType()).isEqualTo("image/png");
        assertThat(resp.providerJobId()).isNotBlank();
    }

    @Test
    void keywordDogIsCaseInsensitive() {
        MockProvider p = provider(defaults(), 1);
        ProviderResponse resp = p.generate(
                new GenerationRequest("A PUPPY and a dog", null, "sd-xl", "512x512"));

        assertThat(resp.data()).isEqualTo(MockProvider.imageForSeed("dog"));
    }

    @Test
    void keywordCityReturnsCityImage() {
        MockProvider p = provider(defaults(), 1);
        ProviderResponse resp = p.generate(
                new GenerationRequest("night urban view", null, "sd-xl", "512x512"));

        assertThat(resp.data()).isEqualTo(MockProvider.imageForSeed("city"));
    }

    @Test
    void unknownPromptReturnsSomeSeedImage() {
        MockProvider p = provider(defaults(), 42);
        ProviderResponse resp = p.generate(ANY);

        assertThat(resp.data()).isNotEmpty();
    }

    @Test
    void blankPromptReturnsSomeSeedImage() {
        MockProvider p = provider(defaults(), 42);
        ProviderResponse resp = p.generate(
                new GenerationRequest("", null, "sd-xl", "512x512"));

        assertThat(resp.data()).isNotEmpty();
    }

    @Test
    void rateLimitErrorWhenRate429IsOne() {
        MockProvider p = provider(new MockProviderProperties(0, 0, 1.0, 0.0), 1);

        assertThatThrownBy(() -> p.generate(ANY))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).httpStatus()).isEqualTo(429));
    }

    @Test
    void serverErrorWhenRate5xxIsOne() {
        MockProvider p = provider(new MockProviderProperties(0, 0, 0.0, 1.0), 1);

        assertThatThrownBy(() -> p.generate(ANY))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).httpStatus()).isEqualTo(500));
    }

    @Test
    void noErrorWhenRatesAreZero() {
        MockProvider p = provider(new MockProviderProperties(0, 0, 0.0, 0.0), 1);
        for (int i = 0; i < 50; i++) {
            assertThat(p.generate(ANY).data()).isNotEmpty();
        }
    }

    @Test
    void latencyIsApplied() {
        MockProvider p = provider(new MockProviderProperties(80, 0, 0.0, 0.0), 1);
        long t0 = System.nanoTime();

        p.generate(ANY);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(70L);
    }

    @Test
    void reportsNameAndSupports() {
        MockProvider p = provider(defaults(), 1);

        assertThat(p.name()).isEqualTo("mock");
        assertThat(p.supports("any-model")).isTrue();
        assertThat(p.supports(null)).isTrue();
    }

    @Test
    void imageForSeedIsDeterministicAndDistinct() {
        byte[] cat1 = MockProvider.imageForSeed("cat");
        byte[] cat2 = MockProvider.imageForSeed("cat");
        byte[] dog = MockProvider.imageForSeed("dog");

        assertThat(cat1).isEqualTo(cat2);
        assertThat(cat1).isNotEqualTo(dog);
        assertThat(cat1.length).isGreaterThan(0);
    }
}