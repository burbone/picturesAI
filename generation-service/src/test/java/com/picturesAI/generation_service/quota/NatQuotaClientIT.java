package com.picturesAI.generation_service.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class NatQuotaClientIT {

    enum ResponderMode {
        ALLOW,
        DENY,
        TIMEOUT,
        NO_RESPONDER
    }

    @Container
    static GenericContainer<?> nats = new GenericContainer<>("nats:2.10-alpine")
            .withCommand("-js")
            .withExposedPorts(4222);

    private Connection responderConnection;
    private NatQuotaClient client;

    @BeforeEach
    void setUp() throws Exception {
        Options options = new Options.Builder()
                .server("nats://" + nats.getHost() + ":" + nats.getMappedPort(4222))
                .connectionTimeout(Duration.ofSeconds(5))
                .build();
        responderConnection = Nats.connect(options);
        client = new NatQuotaClient(responderConnection, new ObjectMapper(), Duration.ofMillis(400));
    }

    @AfterEach
    void tearDown() {
        Connection c = responderConnection;
        responderConnection = null;
        if (c != null) {
            try {
                c.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void startResponder(AtomicReference<ResponderMode> mode) throws Exception {
        Dispatcher dispatcher = responderConnection.createDispatcher(msg -> {
            String reply = msg.getReplyTo();
            if (reply == null) {
                return;
            }
            ResponderMode m = mode.get();
            switch (m) {
                case ALLOW -> responderConnection.publish(reply,
                        "{\"allowed\":true}".getBytes(StandardCharsets.UTF_8));
                case DENY -> responderConnection.publish(reply,
                        "{\"allowed\":false}".getBytes(StandardCharsets.UTF_8));
                case TIMEOUT, NO_RESPONDER -> {
                }
            }
        });
        if (mode.get() != ResponderMode.NO_RESPONDER) {
            dispatcher.subscribe(NatQuotaClient.SUBJECT_RESERVE);
            dispatcher.subscribe(NatQuotaClient.SUBJECT_CONFIRM);
            dispatcher.subscribe(NatQuotaClient.SUBJECT_RELEASE);
        }
        responderConnection.flush(Duration.ofSeconds(2));
    }

    @Test
    void reserveReturnsTrueWhenAllowed() throws Exception {
        AtomicReference<ResponderMode> mode = new AtomicReference<>(ResponderMode.ALLOW);
        startResponder(mode);

        assertThat(client.reserve(UUID.randomUUID(), UUID.randomUUID())).isTrue();
    }

    @Test
    void reserveReturnsFalseWhenDenied() throws Exception {
        AtomicReference<ResponderMode> mode = new AtomicReference<>(ResponderMode.DENY);
        startResponder(mode);

        assertThat(client.reserve(UUID.randomUUID(), UUID.randomUUID())).isFalse();
    }

    @Test
    void reserveReturnsFalseOnTimeout() throws Exception {
        AtomicReference<ResponderMode> mode = new AtomicReference<>(ResponderMode.TIMEOUT);
        startResponder(mode);

        assertThat(client.reserve(UUID.randomUUID(), UUID.randomUUID())).isFalse();
    }

    @Test
    void releaseIsIdempotent() throws Exception {
        AtomicReference<ResponderMode> mode = new AtomicReference<>(ResponderMode.ALLOW);
        startResponder(mode);
        UUID generationId = UUID.randomUUID();

        client.release(generationId);
        client.release(generationId);
    }
}