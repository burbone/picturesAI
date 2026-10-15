package com.picturesAI.generation_service.storage;

import com.picturesAI.generation_service.config.MinioProperties;
import com.picturesAI.generation_service.provider.mock.MockProvider;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MinioImageStorageIT {

    @Container
    static GenericContainer<?> minio = new GenericContainer<>(
            DockerImageName.parse("pgsty/minio:latest"))
            .withEnv("MINIO_ROOT_USER", "picturesAI")
            .withEnv("MINIO_ROOT_PASSWORD", "picturesAI")
            .withCommand("server /data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready")
                    .forPort(9000)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    private MinioImageStorage storage;

    @BeforeEach
    void setUp() {
        String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials("picturesAI", "picturesAI")
                .build();
        MinioProperties props = new MinioProperties(
                endpoint,
                "picturesAI",
                "picturesAI",
                "ai-gallery-originals-test",
                "ai-gallery-thumbnails-test",
                3600);
        storage = new MinioImageStorage(client, props, new ThumbnailGenerator());
        storage.ensureBuckets();
    }

    @Test
    void storesImageAndReturnsPresignedUrls() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        byte[] png = MockProvider.imageForSeed("cat");

        ImageStorage.StoredImage stored = storage.store(userId, generationId, png, "image/png");

        assertThat(stored.imageUrl()).contains("ai-gallery-originals-test");
        assertThat(stored.thumbnailUrl()).contains("ai-gallery-thumbnails-test");

        assertThat(download(stored.imageUrl())).isEqualTo(png);
        assertThat(download(stored.thumbnailUrl())).isNotEmpty();
    }

    @Test
    void usesAnonymousPrefixWhenUserIdIsNull() throws Exception {
        UUID generationId = UUID.randomUUID();
        byte[] png = MockProvider.imageForSeed("dog");

        ImageStorage.StoredImage stored = storage.store(null, generationId, png, "image/png");

        assertThat(stored.imageUrl()).contains("anonymous");
        assertThat(download(stored.imageUrl())).isEqualTo(png);
    }

    @Test
    void overwritesSameGenerationId() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        byte[] cat = MockProvider.imageForSeed("cat");
        byte[] dog = MockProvider.imageForSeed("dog");

        storage.store(userId, generationId, cat, "image/png");
        ImageStorage.StoredImage second = storage.store(userId, generationId, dog, "image/png");

        assertThat(download(second.imageUrl())).isEqualTo(dog);
    }

    private byte[] download(String url) throws Exception {
        URL u = URI.create(url).toURL();
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setRequestMethod("GET");
        try (var is = conn.getInputStream()) {
            return is.readAllBytes();
        } finally {
            conn.disconnect();
        }
    }
}