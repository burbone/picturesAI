package com.picturesAI.catalog_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "catalog.minio")
public record MinioProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        int presignedTtlSeconds) {
}