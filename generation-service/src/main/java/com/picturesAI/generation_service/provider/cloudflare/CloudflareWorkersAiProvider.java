package com.picturesAI.generation_service.provider.cloudflare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Profile({"demo", "prod"})
@Order(1)
@ConditionalOnProperty(prefix = "cloudflare", name = "api-token", matchIfMissing = false)
public class CloudflareWorkersAiProvider implements ImageProvider {

    private static final Logger log = LoggerFactory.getLogger(CloudflareWorkersAiProvider.class);

    private final CloudflareProperties properties;
    private final ObjectMapper mapper;
    private final RestClient restClient;

    public CloudflareWorkersAiProvider(CloudflareProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory(properties.timeout()))
                .build();
    }

    @Override
    public ProviderResponse generate(GenerationRequest request) {
        String url = properties.baseUrl()
                + "/accounts/" + properties.accountId()
                + "/ai/run/" + properties.model();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", buildPrompt(request));
        body.put("steps", properties.steps());

        log.info("Cloudflare Workers AI request: model={} steps={} hasToken={}",
                properties.model(), properties.steps(), StringUtils.hasText(properties.apiToken()));

        try {
            ResponseEntity<byte[]> response = restClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(byte[].class);

            byte[] raw = response.getBody();
            if (raw == null || raw.length == 0) {
                throw new ProviderException(502, "Empty response from Cloudflare Workers AI", true);
            }

            byte[] image = extractImage(raw);
            return new ProviderResponse(image, detectContentType(image), null, name());
        } catch (ProviderException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            String errorBody = e.getResponseBodyAsString();
            log.warn("Cloudflare Workers AI HTTP {} response: {}", status, truncate(errorBody));
            if (status == 401 || status == 403) {
                throw new ProviderException(status, "Cloudflare Workers AI invalid api token", false, e);
            }
            if (status == 429) {
                throw new ProviderException(429, "Cloudflare Workers AI daily neurons exhausted", true, e);
            }
            throw new ProviderException(status, "Cloudflare Workers AI client error", false, e);
        } catch (HttpServerErrorException e) {
            throw new ProviderException(e.getStatusCode().value(),
                    "Cloudflare Workers AI server error", true, e);
        } catch (ResourceAccessException e) {
            throw new ProviderException(503, "Cloudflare Workers AI timeout or IO error", true, e);
        } catch (Exception e) {
            throw new ProviderException(503, "Cloudflare Workers AI unexpected error", false, e);
        }
    }

    @Override
    public String name() {
        return "cloudflare-workers-ai";
    }

    @Override
    public boolean supports(String model) {
        return true;
    }

    private byte[] extractImage(byte[] raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            boolean success = root.path("success").asBoolean(false);
            if (!success) {
                String errors = root.path("errors").toString();
                throw new ProviderException(502,
                        "Cloudflare Workers AI errors: " + truncate(errors), true);
            }
            String base64 = root.path("result").path("image").asText(null);
            if (base64 == null || base64.isBlank()) {
                throw new ProviderException(502, "Cloudflare Workers AI empty image field", true);
            }
            return Base64.getDecoder().decode(base64);
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException(502, "Cloudflare Workers AI invalid response", true, e);
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

    private static String detectContentType(byte[] data) {
        if (data.length >= 4) {
            if ((data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
                return "image/png";
            }
            if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
                return "image/jpeg";
            }
        }
        return "image/png";
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = Math.toIntExact(timeout.toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }
}