package com.picturesAI.generation_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "minio")
public record MinioProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String originalsBucket,
        String thumbnailsBucket,
        int presignedTtlSeconds) {

    public MinioProperties {
        if (presignedTtlSeconds <= 0) {
            presignedTtlSeconds = 7 * 24 * 3600;
        }
    }
}