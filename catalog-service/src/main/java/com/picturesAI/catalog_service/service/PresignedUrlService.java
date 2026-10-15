package com.picturesAI.catalog_service.service;

import com.picturesAI.catalog_service.config.MinioProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class PresignedUrlService {

    private final MinioClient client;
    private final MinioProperties properties;

    public PresignedUrlService(MinioClient client, MinioProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public String presign(String bucket, String key) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(key)
                    .expiry(properties.presignedTtlSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to presign " + bucket + "/" + key, e);
        }
    }
}