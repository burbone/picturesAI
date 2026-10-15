package com.picturesAI.tagging_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "tagging.keyword")
public record KeywordProperties(int maxTags, BigDecimal minWeight) {

    public KeywordProperties {
        if (maxTags <= 0) {
            maxTags = 10;
        }
        if (minWeight == null || minWeight.signum() < 0) {
            minWeight = new BigDecimal("0.1");
        }
    }
}