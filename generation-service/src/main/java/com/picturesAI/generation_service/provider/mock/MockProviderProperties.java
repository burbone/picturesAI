package com.picturesAI.generation_service.provider.mock;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mock")
public record MockProviderProperties(
        long latencyMs,
        long latencyJitterMs,
        double errorRate429,
        double errorRate5xx) {

    public MockProviderProperties {
        if (latencyMs < 0) {
            latencyMs = 0;
        }
        if (latencyJitterMs < 0) {
            latencyJitterMs = 0;
        }
        if (errorRate429 < 0) {
            errorRate429 = 0;
        }
        if (errorRate429 > 1) {
            errorRate429 = 1;
        }
        if (errorRate5xx < 0) {
            errorRate5xx = 0;
        }
        if (errorRate5xx > 1) {
            errorRate5xx = 1;
        }
    }
}