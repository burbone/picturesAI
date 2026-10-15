package com.picturesAI.generation_service.quota;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import io.nats.client.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("!dev & !demo-local")
public class NatQuotaClient implements QuotaClient {

    public static final String SUBJECT_RESERVE = "billing.reserveQuota";
    public static final String SUBJECT_CONFIRM = "billing.confirmQuota";
    public static final String SUBJECT_RELEASE = "billing.releaseQuota";

    private static final Logger log = LoggerFactory.getLogger(NatQuotaClient.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final Connection connection;
    private final ObjectMapper mapper;
    private final Duration timeout;

    @Autowired
    public NatQuotaClient(Connection connection, ObjectMapper mapper) {
        this(connection, mapper, DEFAULT_TIMEOUT);
    }

    public NatQuotaClient(Connection connection, ObjectMapper mapper, Duration timeout) {
        this.connection = connection;
        this.mapper = mapper;
        this.timeout = timeout;
    }

    @Override
    public boolean reserve(UUID userId, UUID generationId) {
        try {
            byte[] payload = mapper.writeValueAsBytes(Map.of(
                    "userId", userId.toString(),
                    "generationId", generationId.toString()));
            Message reply = connection.request(SUBJECT_RESERVE, payload, timeout);
            JsonNode node = mapper.readTree(reply.getData());
            return node.path("allowed").asBoolean(false);
        } catch (Exception e) {
            log.warn("Quota reserve failed for generation {}: {}", generationId, e.toString());
            return false;
        }
    }

    @Override
    public void confirm(UUID generationId) {
        try {
            byte[] payload = mapper.writeValueAsBytes(Map.of(
                    "generationId", generationId.toString()));
            connection.request(SUBJECT_CONFIRM, payload, timeout);
        } catch (Exception e) {
            log.warn("Quota confirm failed for generation {}: {}", generationId, e.toString());
        }
    }

    @Override
    public void release(UUID generationId) {
        try {
            byte[] payload = mapper.writeValueAsBytes(Map.of(
                    "generationId", generationId.toString()));
            connection.request(SUBJECT_RELEASE, payload, timeout);
        } catch (Exception e) {
            log.warn("Quota release failed for generation {}: {}", generationId, e.toString());
        }
    }
}