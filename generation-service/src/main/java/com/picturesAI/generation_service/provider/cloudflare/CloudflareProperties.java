package com.picturesAI.generation_service.provider.cloudflare;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "cloudflare")
public record CloudflareProperties(
        String accountId,
        String apiToken,
        String baseUrl,
        String model,
        Integer steps,
        Duration timeout
) {
    public CloudflareProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.cloudflare.com/client/v4";
        }
        if (model == null || model.isBlank()) {
            model = "@cf/black-forest-labs/flux-1-schnell";
        }
        if (steps == null || steps <= 0) {
            steps = 4;
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
    }
}