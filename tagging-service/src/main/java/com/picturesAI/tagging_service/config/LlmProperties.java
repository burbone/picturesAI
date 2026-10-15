package com.picturesAI.tagging_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tagging.llm")
public record LlmProperties(String endpoint, String model, int timeoutMs, int maxTags) {

    public LlmProperties {
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://127.0.0.1:11434";
        }
        if (model == null || model.isBlank()) {
            model = "qwen2.5:1.5b";
        }
        if (timeoutMs <= 0) {
            timeoutMs = 10_000;
        }
        if (maxTags <= 0) {
            maxTags = 10;
        }
    }
}