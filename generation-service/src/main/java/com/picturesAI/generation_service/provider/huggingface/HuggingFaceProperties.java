package com.picturesAI.generation_service.provider.huggingface;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "huggingface")
public record HuggingFaceProperties(
        String apiKey,
        String baseUrl,
        Duration timeout,
        String defaultModel,
        RateLimit rateLimit
) {

    public record RateLimit(Long minIntervalMs) {
        public RateLimit {
            if (minIntervalMs == null) minIntervalMs = 15000L;
            if (minIntervalMs < 0) minIntervalMs = 0L;
        }
    }

    public HuggingFaceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api-inference.huggingface.co";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
        if (defaultModel == null || defaultModel.isBlank()) {
            defaultModel = "black-forest-labs/FLUX.1-schnell";
        }
        if (rateLimit == null) {
            rateLimit = new RateLimit(null);
        }
    }
}