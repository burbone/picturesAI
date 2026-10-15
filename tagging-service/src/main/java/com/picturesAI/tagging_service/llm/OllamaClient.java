package com.picturesAI.tagging_service.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.tagging_service.config.LlmProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OllamaClient {

    private static final int MAX_ATTEMPTS = 2;

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final RestClient restClient;

    public OllamaClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory(props.timeoutMs()))
                .build();
    }

    public String generate(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("prompt", prompt);
        body.put("stream", false);
        body.put("format", "json");

        LlmException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return doCall(body);
            } catch (LlmException e) {
                last = e;
                if (!e.retryable()) {
                    throw e;
                }
            }
        }
        throw last;
    }

    private String doCall(Map<String, Object> body) {
        try {
            String raw = restClient.post()
                    .uri(props.endpoint() + "/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            if (raw == null || raw.isBlank()) {
                throw new LlmException("Empty response from Ollama", false);
            }

            JsonNode root;
            try {
                root = mapper.readTree(raw);
            } catch (JsonProcessingException e) {
                throw new LlmException("Ollama response is not valid JSON", false, e);
            }

            JsonNode responseNode = root.path("response");
            if (!responseNode.isTextual()) {
                throw new LlmException("Ollama response has no textual 'response' field", false);
            }
            return responseNode.asText();
        } catch (LlmException e) {
            throw e;
        } catch (HttpServerErrorException e) {
            throw new LlmException("Ollama 5xx: " + e.getStatusCode().value(), true, e);
        } catch (HttpClientErrorException e) {
            throw new LlmException("Ollama 4xx: " + e.getStatusCode().value(), false, e);
        } catch (ResourceAccessException e) {
            throw new LlmException("Ollama timeout/IO", true, e);
        } catch (Exception e) {
            boolean retryable = hasIoCause(e);
            throw new LlmException("Ollama unexpected error", retryable, e);
        }
    }

    private static boolean hasIoCause(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static SimpleClientHttpRequestFactory requestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return factory;
    }
}