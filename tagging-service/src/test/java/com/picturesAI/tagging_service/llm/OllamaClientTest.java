package com.picturesAI.tagging_service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.picturesAI.tagging_service.config.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private OllamaClient client;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        LlmProperties props = new LlmProperties(
                wireMock.baseUrl(), "qwen2.5:1.5b", 1000, 10);
        client = new OllamaClient(props, new ObjectMapper());
    }

    @Test
    void returnsResponseFieldOn200() {
        wireMock.stubFor(post(urlEqualTo("/api/generate"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"response\":\"[{\\\"tag\\\":\\\"cat\\\",\\\"weight\\\":0.9}]\",\"done\":true}")));

        String result = client.generate("a cat");

        assertThat(result).isEqualTo("[{\"tag\":\"cat\",\"weight\":0.9}]");

        wireMock.verify(postRequestedFor(urlEqualTo("/api/generate"))
                .withHeader(HttpHeaders.CONTENT_TYPE, containing("application/json"))
                .withRequestBody(equalToJson(
                        "{\"model\":\"qwen2.5:1.5b\",\"prompt\":\"a cat\",\"stream\":false,\"format\":\"json\"}")));
    }

    @Test
    void bodyWithoutResponseFieldFailsWithoutRetry() {
        wireMock.stubFor(post(urlEqualTo("/api/generate"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"done\":true}")));

        assertThatThrownBy(() -> client.generate("a cat"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("response");

        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/generate")));
    }

    @Test
    void nonJsonBodyFailsWithoutRetry() {
        wireMock.stubFor(post(urlEqualTo("/api/generate"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("not-json")));

        assertThatThrownBy(() -> client.generate("a cat"))
                .isInstanceOf(LlmException.class);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/generate")));
    }

    @Test
    void serverErrorRetriesOnceThenFails() {
        wireMock.stubFor(post(urlEqualTo("/api/generate"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.generate("a cat"))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).retryable()).isTrue());

        wireMock.verify(2, postRequestedFor(urlEqualTo("/api/generate")));
    }

    @Test
    void clientErrorFailsWithoutRetry() {
        wireMock.stubFor(post(urlEqualTo("/api/generate"))
                .willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> client.generate("a cat"))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).retryable()).isFalse());

        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/generate")));
    }

    @Test
    void timeoutRetriesOnceThenFails() {
        LlmProperties fastTimeout = new LlmProperties(
                wireMock.baseUrl(), "qwen2.5:1.5b", 150, 10);
        OllamaClient fast = new OllamaClient(fastTimeout, new ObjectMapper());

        wireMock.stubFor(post(urlEqualTo("/api/generate"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(500)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"response\":\"[]\",\"done\":true}")));

        assertThatThrownBy(() -> fast.generate("a cat"))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).retryable()).isTrue());

        wireMock.verify(2, postRequestedFor(urlEqualTo("/api/generate")));
    }
}