package com.picturesAI.notification_service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class GenerationServiceClient {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLES_HEADER = "X-User-Roles";
    private static final String USER_NAME_HEADER = "X-User-Name";

    private final WebClient webClient;
    private final Duration timeout;

    public GenerationServiceClient(
            @Value("${generation-service.base-url}") String baseUrl,
            @Value("${generation-service.timeout-ms}") long timeoutMs) {
        this.timeout = Duration.ofMillis(timeoutMs);
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeoutMs)
                .responseTimeout(timeout);
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    public Mono<GenerationStatusResult> fetchStatus(UUID generationId, UUID userId, Set<String> roles, String username) {
        String rolesCsv = roles == null ? "" : roles.stream().sorted().collect(Collectors.joining(","));
        return webClient.get()
                .uri("/api/v1/generations/{id}/status", generationId)
                .header(USER_ID_HEADER, userId.toString())
                .header(USER_ROLES_HEADER, rolesCsv)
                .header(USER_NAME_HEADER, username == null ? "" : username)
                .exchangeToMono(this::mapResponse)
                .timeout(timeout)
                .onErrorResume(this::mapError);
    }

    private Mono<GenerationStatusResult> mapResponse(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        if (status.is2xxSuccessful()) {
            return response.bodyToMono(JsonNode.class)
                    .map(body -> new GenerationStatusResult(GenerationAccessResult.OK, body))
                    .defaultIfEmpty(new GenerationStatusResult(GenerationAccessResult.OK, null));
        }
        if (status.value() == 404) {
            return response.releaseBody()
                    .thenReturn(new GenerationStatusResult(GenerationAccessResult.NOT_FOUND, null));
        }
        if (status.value() == 403) {
            return response.releaseBody()
                    .thenReturn(new GenerationStatusResult(GenerationAccessResult.FORBIDDEN, null));
        }
        return response.releaseBody()
                .thenReturn(new GenerationStatusResult(GenerationAccessResult.INTERNAL_ERROR, null));
    }

    private Mono<GenerationStatusResult> mapError(Throwable throwable) {
        if (throwable instanceof WebClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            if (status.value() == 404) {
                return Mono.just(new GenerationStatusResult(GenerationAccessResult.NOT_FOUND, null));
            }
            if (status.value() == 403) {
                return Mono.just(new GenerationStatusResult(GenerationAccessResult.FORBIDDEN, null));
            }
            return Mono.just(new GenerationStatusResult(GenerationAccessResult.INTERNAL_ERROR, null));
        }
        if (throwable instanceof WebClientRequestException) {
            return Mono.just(new GenerationStatusResult(GenerationAccessResult.INTERNAL_ERROR, null));
        }
        return Mono.just(new GenerationStatusResult(GenerationAccessResult.INTERNAL_ERROR, null));
    }
}