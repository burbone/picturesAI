package com.picturesAI.generation_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.picturesAI.generation_service.config.MinioProperties;
import com.picturesAI.generation_service.domain.Generation;
import com.picturesAI.generation_service.domain.GenerationStatus;
import com.picturesAI.generation_service.domain.OutboxEvent;
import com.picturesAI.generation_service.domain.Tier;
import com.picturesAI.generation_service.provider.ImageProvider;
import com.picturesAI.generation_service.provider.ProviderFallbackChain;
import com.picturesAI.generation_service.provider.cloudflare.CloudflareProperties;
import com.picturesAI.generation_service.provider.cloudflare.CloudflareWorkersAiProvider;
import com.picturesAI.generation_service.provider.huggingface.HuggingFaceProperties;
import com.picturesAI.generation_service.provider.huggingface.HuggingFaceProvider;
import com.picturesAI.generation_service.provider.mock.MockProvider;
import com.picturesAI.generation_service.quota.QuotaClient;
import com.picturesAI.generation_service.repository.GenerationRepository;
import com.picturesAI.generation_service.repository.OutboxRepository;
import com.picturesAI.generation_service.storage.MinioImageStorage;
import com.picturesAI.generation_service.storage.ThumbnailGenerator;
import com.picturesAI.generation_service.worker.GenerationTask;
import com.picturesAI.generation_service.worker.cancel.CancellationRegistry;
import com.picturesAI.generation_service.worker.retry.GenerationRetryService;
import com.picturesAI.generation_service.worker.retry.ProviderErrorClassifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
class GenerationProcessingServiceIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Container
    static GenericContainer<?> minio = new GenericContainer<>(
            DockerImageName.parse("pgsty/minio:latest"))
            .withEnv("MINIO_ROOT_USER", "test")
            .withEnv("MINIO_ROOT_PASSWORD", "test12345")
            .withCommand("server /data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready")
                    .forPort(9000)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    private MinioImageStorage storage;
    private GenerationRepository generations;
    private OutboxRepository outbox;
    private QuotaClient quota;
    private CancellationRegistry cancellation;
    private GenerationRetryService retryService;
    private GenerationProcessingService service;

    @BeforeEach
    void setUp() {
        String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        MinioClient minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials("test", "test12345")
                .build();
        MinioProperties minioProps = new MinioProperties(
                endpoint,
                "test",
                "test12345",
                "gen-test-originals-" + UUID.randomUUID(),
                "gen-test-thumbs-" + UUID.randomUUID(),
                3600);
        storage = new MinioImageStorage(minioClient, minioProps, new ThumbnailGenerator());
        storage.ensureBuckets();

        CloudflareProperties cfProps = new CloudflareProperties(
                "test-account",
                "test-token",
                wireMock.baseUrl(),
                "@cf/black-forest-labs/flux-1-schnell",
                4,
                Duration.ofSeconds(5));
        CloudflareWorkersAiProvider cloudflare =
                new CloudflareWorkersAiProvider(cfProps, new ObjectMapper());

        HuggingFaceProperties hfProps = new HuggingFaceProperties(
                "test-key",
                wireMock.baseUrl(),
                Duration.ofSeconds(5),
                "black-forest-labs/FLUX.1-schnell",
                new HuggingFaceProperties.RateLimit(0L));
        HuggingFaceProvider huggingFace = new HuggingFaceProvider(hfProps);

        ImageProvider chain = new ProviderFallbackChain(List.of(cloudflare, huggingFace));

        generations = mock(GenerationRepository.class);
        outbox = mock(OutboxRepository.class);
        quota = mock(QuotaClient.class);
        cancellation = mock(CancellationRegistry.class);
        when(cancellation.isMarked(any())).thenReturn(false);
        retryService = mock(GenerationRetryService.class);

        service = new GenerationProcessingService(
                generations,
                outbox,
                List.of(chain),
                storage,
                quota,
                cancellation,
                new ProviderErrorClassifier(),
                retryService,
                new ObjectMapper(),
                new TransactionTemplate(new NoopTransactionManager()),
                new SimpleMeterRegistry());
    }

    @Test
    void process_cloudflareImageStoredAndPresignedUrlReachable() throws Exception {
        UUID genId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        byte[] realPng = MockProvider.imageForSeed("cat");
        String base64 = Base64.getEncoder().encodeToString(realPng);
        String cfBody = "{\"result\":{\"image\":\"" + base64 + "\"},\"success\":true}";

        wireMock.stubFor(post(urlPathMatching("/accounts/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                        .withBody(cfBody)));

        Generation g = queuedGeneration(genId, userId, "cat");
        when(generations.findById(genId)).thenReturn(Optional.of(g));
        when(generations.transitionToProcessing(any(), any(), any(), any())).thenReturn(1);
        when(generations.transitionToCompleted(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.process(new GenerationTask(genId, Tier.FREE));

        ArgumentCaptor<String> imageUrlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> thumbUrlCap = ArgumentCaptor.forClass(String.class);
        verify(generations).transitionToCompleted(
                eq(genId),
                eq(GenerationStatus.PROCESSING),
                eq(GenerationStatus.COMPLETED),
                imageUrlCap.capture(),
                thumbUrlCap.capture(),
                eq("cloudflare-workers-ai"),
                any(),
                any());

        assertThat(imageUrlCap.getValue()).contains("gen-test-originals-");
        assertThat(thumbUrlCap.getValue()).contains("gen-test-thumbs-");

        assertThat(download(imageUrlCap.getValue())).isEqualTo(realPng);
        assertThat(download(thumbUrlCap.getValue())).isNotEmpty();

        verify(outbox).save(any(OutboxEvent.class));
        verify(quota).confirm(genId);
        wireMock.verify(1, postRequestedFor(urlPathMatching("/accounts/.*")));
        wireMock.verify(0, postRequestedFor(urlPathMatching("/models/.*")));
    }

    @Test
    void process_cloudflare500_fallsBackToHuggingFace() throws Exception {
        UUID genId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        byte[] hfPng = MockProvider.imageForSeed("dog");

        wireMock.stubFor(post(urlPathMatching("/accounts/.*"))
                .willReturn(aResponse().withStatus(500)));
        wireMock.stubFor(post(urlPathMatching("/models/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, "image/png")
                        .withBody(hfPng)));

        Generation g = queuedGeneration(genId, userId, "dog");
        when(generations.findById(genId)).thenReturn(Optional.of(g));
        when(generations.transitionToProcessing(any(), any(), any(), any())).thenReturn(1);
        when(generations.transitionToCompleted(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.process(new GenerationTask(genId, Tier.FREE));

        ArgumentCaptor<String> imageUrlCap = ArgumentCaptor.forClass(String.class);
        verify(generations).transitionToCompleted(
                eq(genId),
                eq(GenerationStatus.PROCESSING),
                eq(GenerationStatus.COMPLETED),
                imageUrlCap.capture(),
                any(),
                eq("huggingface"),
                any(),
                any());

        assertThat(download(imageUrlCap.getValue())).isEqualTo(hfPng);

        wireMock.verify(1, postRequestedFor(urlPathMatching("/accounts/.*")));
        wireMock.verify(1, postRequestedFor(urlPathMatching("/models/.*")));
    }

    private static Generation queuedGeneration(UUID id, UUID userId, String prompt) {
        Generation g = new Generation();
        g.setId(id);
        g.setUserId(userId);
        g.setPrompt(prompt);
        g.setModel("flux");
        g.setSize("512x512");
        g.setTier(Tier.FREE);
        g.setStatus(GenerationStatus.QUEUED);
        g.setCreatedAt(Instant.now());
        return g;
    }

    private static byte[] download(String url) throws Exception {
        URL u = URI.create(url).toURL();
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        try (var is = conn.getInputStream()) {
            return is.readAllBytes();
        } finally {
            conn.disconnect();
        }
    }

    private static final class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}