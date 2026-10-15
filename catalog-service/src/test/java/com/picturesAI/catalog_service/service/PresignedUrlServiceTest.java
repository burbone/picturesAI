package com.picturesAI.catalog_service.service;

import com.picturesAI.catalog_service.config.MinioProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresignedUrlServiceTest {

    @Mock
    private MinioClient client;

    private PresignedUrlService service;

    @BeforeEach
    void setUp() {
        MinioProperties props = new MinioProperties(
                "http://127.0.0.1:9000", "picturesAI", "picturesAI", 3600);
        service = new PresignedUrlService(client, props);
    }

    @Test
    void presignReturnsUrlFromClient() throws Exception {
        when(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://minio/presigned");

        String url = service.presign("ai-gallery-originals", "u/g.png");

        assertThat(url).isEqualTo("http://minio/presigned");
    }

    @Test
    void presignUsesConfiguredBucketKeyAndTtl() throws Exception {
        when(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://minio/presigned");

        service.presign("ai-gallery-originals", "u/g.png");

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor =
                ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(client).getPresignedObjectUrl(captor.capture());
        GetPresignedObjectUrlArgs args = captor.getValue();
        assertThat(args.bucket()).isEqualTo("ai-gallery-originals");
        assertThat(args.object()).isEqualTo("u/g.png");
        assertThat(args.expiry()).isEqualTo(3600);
    }

    @Test
    void presignWrapsClientFailure() throws Exception {
        lenient().when(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> service.presign("b", "k"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("b/k");
    }
}