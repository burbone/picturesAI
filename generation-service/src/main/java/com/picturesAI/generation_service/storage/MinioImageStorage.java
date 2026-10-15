package com.picturesAI.generation_service.storage;

import com.picturesAI.generation_service.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class MinioImageStorage implements ImageStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioImageStorage.class);
    private static final int THUMBNAIL_MAX_SIZE = 200;
    private static final int ENSURE_BUCKET_ATTEMPTS = 15;
    private static final long ENSURE_BUCKET_BACKOFF_MS = 1000L;

    private final MinioClient client;
    private final MinioProperties props;
    private final ThumbnailGenerator thumbnailGenerator;

    public MinioImageStorage(MinioClient client,
                             MinioProperties props,
                             ThumbnailGenerator thumbnailGenerator) {
        this.client = client;
        this.props = props;
        this.thumbnailGenerator = thumbnailGenerator;
    }

    @PostConstruct
    public void ensureBuckets() {
        ensureBucket(props.originalsBucket());
        ensureBucket(props.thumbnailsBucket());
    }

    @Override
    public StoredImage store(UUID userId, UUID generationId, byte[] data, String contentType) {
        String prefix = userId == null ? "anonymous" : userId.toString();
        String objectName = prefix + "/" + generationId + ".png";
        byte[] thumbnail = thumbnailGenerator.generate(data, THUMBNAIL_MAX_SIZE);

        putObject(props.originalsBucket(), objectName, data, contentType);
        putObject(props.thumbnailsBucket(), objectName, thumbnail, "image/png");

        String imageUrl = presignedUrl(props.originalsBucket(), objectName);
        String thumbUrl = presignedUrl(props.thumbnailsBucket(), objectName);

        return new StoredImage(
                imageUrl,
                thumbUrl,
                props.originalsBucket(),
                objectName,
                props.thumbnailsBucket(),
                objectName);
    }

    private void ensureBucket(String bucket) {
        Exception last = null;
        for (int attempt = 1; attempt <= ENSURE_BUCKET_ATTEMPTS; attempt++) {
            try {
                boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("Created MinIO bucket {}", bucket);
                }
                return;
            } catch (Exception e) {
                last = e;
                log.warn("ensureBucket {} attempt {}/{} failed: {}",
                        bucket, attempt, ENSURE_BUCKET_ATTEMPTS, e.getMessage());
                if (attempt < ENSURE_BUCKET_ATTEMPTS) {
                    sleep(ENSURE_BUCKET_BACKOFF_MS);
                }
            }
        }
        throw new IllegalStateException("Failed to ensure MinIO bucket " + bucket, last);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for MinIO", e);
        }
    }

    private void putObject(String bucket, String objectName, byte[] data, String contentType) {
        try (InputStream is = new ByteArrayInputStream(data)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(is, data.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to put object " + bucket + "/" + objectName, e);
        }
    }

    private String presignedUrl(String bucket, String objectName) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectName)
                    .expiry(props.presignedTtlSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to presign " + bucket + "/" + objectName, e);
        }
    }
}