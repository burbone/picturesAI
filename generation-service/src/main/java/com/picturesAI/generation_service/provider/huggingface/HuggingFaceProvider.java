package com.picturesAI.generation_service.provider.huggingface;

import com.picturesAI.generation_service.provider.GenerationRequest;
import com.picturesAI.generation_service.provider.ImageProvider;
import com.picturesAI.generation_service.provider.ProviderException;
import com.picturesAI.generation_service.provider.ProviderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Profile({"demo", "prod"})
@Order(2)
@ConditionalOnProperty(prefix = "huggingface", name = "api-key", matchIfMissing = false)
public class HuggingFaceProvider implements ImageProvider {

    private static final Logger log = LoggerFactory.getLogger(HuggingFaceProvider.class);

    private final HuggingFaceProperties properties;
    private final RestClient restClient;
    private final Semaphore semaphore = new Semaphore(1, true);
    private final AtomicLong lastRequestTime = new AtomicLong(0L);

    public HuggingFaceProvider(HuggingFaceProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory(properties.timeout()))
                .build();
    }

    @Override
    public ProviderResponse generate(GenerationRequest request) {
        acquireSemaphore();
        try {
            awaitMinInterval();
            return executeHttpCall(request);
        } finally {
            lastRequestTime.set(System.currentTimeMillis());
            semaphore.release();
        }
    }

    @Override
    public String name() {
        return "huggingface";
    }

    @Override
    public boolean supports(String model) {
        return true;
    }

    private void acquireSemaphore() {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(503, "interrupted", false, e);
        }
    }

    private void awaitMinInterval() {
        long minInterval = properties.rateLimit().minIntervalMs();
        if (minInterval <= 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() - lastRequestTime.get();
        long wait = minInterval - elapsed;
        if (wait > 0) {
            log.info("HuggingFace rate-limit pause for {}ms", wait);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderException(503, "interrupted", false, e);
            }
        }
    }

    private ProviderResponse executeHttpCall(GenerationRequest request) {
        Map<String, Object> body = Map.of("inputs", buildPrompt(request));
        String model = properties.defaultModel();

        try {
            ResponseEntity<byte[]> response = restClient.post()
                    .uri(properties.baseUrl() + "/models/" + model)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(byte[].class);

            byte[] data = response.getBody();
            if (data == null || data.length == 0) {
                throw new ProviderException(502, "Empty response from HuggingFace", true);
            }

            String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            if (contentType == null || !contentType.startsWith("image/")) {
                contentType = MediaType.IMAGE_JPEG_VALUE;
            }

            return new ProviderResponse(data, contentType, null, name());
        } catch (ProviderException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new ProviderException(status, "HuggingFace invalid api key", false, e);
            }
            throw new ProviderException(status, "HuggingFace client error",
                    status == 429 || status >= 500, e);
        } catch (HttpServerErrorException e) {
            throw new ProviderException(e.getStatusCode().value(),
                    "HuggingFace server error", true, e);
        } catch (ResourceAccessException e) {
            throw new ProviderException(503, "HuggingFace timeout or IO error", true, e);
        } catch (Exception e) {
            throw new ProviderException(503, "HuggingFace unexpected error", false, e);
        }
    }

    private String buildPrompt(GenerationRequest request) {
        String prompt = request.prompt() == null ? "" : request.prompt();
        String negative = request.negativePrompt();
        if (negative != null && !negative.isBlank()) {
            return prompt + " " + negative;
        }
        return prompt;
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = Math.toIntExact(timeout.toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }
}