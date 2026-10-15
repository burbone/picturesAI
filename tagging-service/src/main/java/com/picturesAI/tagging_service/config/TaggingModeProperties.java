package com.picturesAI.tagging_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tagging")
public record TaggingModeProperties(String mode) {

    public TaggingModeProperties {
        if (mode == null || mode.isBlank()) {
            mode = "keywords";
        } else {
            mode = mode.trim().toLowerCase();
        }
    }

    public boolean isLlm() {
        return "llm".equals(mode);
    }
}