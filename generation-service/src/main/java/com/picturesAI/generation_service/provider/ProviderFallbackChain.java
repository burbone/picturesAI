package com.picturesAI.generation_service.provider;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Primary
@Order(Ordered.HIGHEST_PRECEDENCE)
@Profile({"demo", "prod"})
public class ProviderFallbackChain implements ImageProvider {

    private static final Logger log = LoggerFactory.getLogger(ProviderFallbackChain.class);

    private final List<ImageProvider> providers;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, AtomicInteger> concurrent = new ConcurrentHashMap<>();

    public ProviderFallbackChain(List<ImageProvider> providers) {
        this(providers, new SimpleMeterRegistry());
    }

    @Autowired
    public ProviderFallbackChain(List<ImageProvider> providers, MeterRegistry meterRegistry) {
        this.providers = providers.stream()
                .filter(p -> !(p instanceof ProviderFallbackChain))
                .toList();
        if (this.providers.isEmpty()) {
            throw new IllegalStateException("No ImageProvider beans available for fallback chain");
        }
        this.meterRegistry = meterRegistry;
        for (ImageProvider p : this.providers) {
            AtomicInteger counter = concurrent.computeIfAbsent(p.name(), k -> new AtomicInteger());
            Gauge.builder("provider_concurrent", counter, AtomicInteger::get)
                    .tag("provider", p.name())
                    .register(meterRegistry);
        }
        log.info("ProviderFallbackChain initialized with providers: {}",
                this.providers.stream().map(ImageProvider::name).toList());
    }

    @Override
    public ProviderResponse generate(GenerationRequest request) {
        ProviderException last = null;
        for (int i = 0; i < providers.size(); i++) {
            ImageProvider provider = providers.get(i);
            String name = provider.name();
            AtomicInteger inFlight = concurrent.get(name);
            inFlight.incrementAndGet();
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                ProviderResponse response = provider.generate(request);
                sample.stop(meterRegistry.timer("provider_duration_seconds", "provider", name));
                meterRegistry.counter("provider_calls_total",
                        "provider", name, "result", "success").increment();
                return response;
            } catch (ProviderException e) {
                sample.stop(meterRegistry.timer("provider_duration_seconds", "provider", name));
                last = e;
                String result = classify(e);
                meterRegistry.counter("provider_calls_total",
                        "provider", name, "result", result).increment();
                if ("rate_limited".equals(result)) {
                    meterRegistry.counter("provider_rate_limited_total", "provider", name).increment();
                } else if ("key_error".equals(result)) {
                    meterRegistry.counter("provider_key_errors_total", "provider", name).increment();
                }
                log.warn("Provider {} failed (attempt {}/{}): status={} msg={}",
                        name, i + 1, providers.size(), e.httpStatus(), e.getMessage());
            } finally {
                inFlight.decrementAndGet();
            }
        }
        throw last;
    }

    @Override
    public String name() {
        return "fallback-chain";
    }

    @Override
    public boolean supports(String model) {
        return providers.stream().anyMatch(p -> p.supports(model));
    }

    private static String classify(ProviderException e) {
        int status = e.httpStatus();
        if (status == 429) {
            return "rate_limited";
        }
        if (status == 401 || status == 403) {
            return "key_error";
        }
        return "error";
    }
}