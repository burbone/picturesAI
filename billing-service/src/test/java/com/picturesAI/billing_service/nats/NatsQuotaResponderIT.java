package com.picturesAI.billing_service.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.billing_service.AbstractPostgresIT;
import com.picturesAI.billing_service.nats.dto.OkResponse;
import com.picturesAI.billing_service.nats.dto.ReserveRequest;
import com.picturesAI.billing_service.nats.dto.ReserveResponse;
import com.picturesAI.billing_service.repository.QuotaReservationRepository;
import com.picturesAI.billing_service.service.QuotaService;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NatsQuotaResponderIT extends AbstractPostgresIT {

    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>(DockerImageName.parse("nats:2.10-alpine"))
            .withExposedPorts(4222);

    static Connection serverConnection;

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private QuotaReservationRepository reservationRepository;

    private Connection clientConnection;
    private QuotaResponder responder;

    @DynamicPropertySource
    static void natsProps(DynamicPropertyRegistry registry) {
        registry.add("nats.url", () -> "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
    }

    @BeforeAll
    static void startServerConnection() throws Exception {
        Options options = new Options.Builder()
                .server("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222))
                .connectionTimeout(Duration.ofSeconds(5))
                .build();
        serverConnection = Nats.connect(options);
    }

    @AfterAll
    static void stopServerConnection() throws Exception {
        if (serverConnection != null) {
            serverConnection.close();
        }
    }

    @BeforeEach
    void setUpResponder() throws Exception {
        NatsProperties properties = new NatsProperties();
        properties.setUrl("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
        responder = new QuotaResponder(serverConnection, objectMapper, quotaService, properties);
        responder.start();

        Options options = new Options.Builder()
                .server("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222))
                .connectionTimeout(Duration.ofSeconds(5))
                .build();
        clientConnection = Nats.connect(options);
    }

    @AfterEach
    void tearDownResponder() throws Exception {
        if (responder != null) {
            responder.stop();
        }
        if (clientConnection != null) {
            clientConnection.close();
        }
    }

    @Test
    void reserveCreatesReservationAndAllows() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();

        ReserveResponse response = request("billing.reserveQuota",
                new ReserveRequest(generationId, userId), ReserveResponse.class);

        assertThat(response.allowed()).isTrue();
        assertThat(response.remaining()).isEqualTo(9);
        assertThat(reservationRepository.findById(generationId)).isPresent();
    }

    @Test
    void reserveDeniesWhenLimitExceeded() throws Exception {
        UUID userId = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            ReserveResponse ok = request("billing.reserveQuota",
                    new ReserveRequest(UUID.randomUUID(), userId), ReserveResponse.class);
            assertThat(ok.allowed()).isTrue();
        }

        ReserveResponse denied = request("billing.reserveQuota",
                new ReserveRequest(UUID.randomUUID(), userId), ReserveResponse.class);

        assertThat(denied.allowed()).isFalse();
    }

    @Test
    void reserveIdempotentOnRepeat() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();

        ReserveResponse first = request("billing.reserveQuota",
                new ReserveRequest(generationId, userId), ReserveResponse.class);
        ReserveResponse second = request("billing.reserveQuota",
                new ReserveRequest(generationId, userId), ReserveResponse.class);

        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isTrue();
        assertThat(second.remaining()).isEqualTo(first.remaining());
        assertThat(reservationRepository.count()).isEqualTo(1L);
    }

    @Test
    void confirmMarksReservationConfirmed() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        request("billing.reserveQuota", new ReserveRequest(generationId, userId), ReserveResponse.class);

        OkResponse response = request("billing.confirmQuota",
                new ReserveRequest(generationId, userId), OkResponse.class);

        assertThat(response.ok()).isTrue();
        assertThat(reservationRepository.findById(generationId).orElseThrow().getStatus().name())
                .isEqualTo("CONFIRMED");
    }

    @Test
    void releaseMarksReservationReleased() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        request("billing.reserveQuota", new ReserveRequest(generationId, userId), ReserveResponse.class);

        OkResponse response = request("billing.releaseQuota",
                new ReserveRequest(generationId, userId), OkResponse.class);

        assertThat(response.ok()).isTrue();
        assertThat(reservationRepository.findById(generationId).orElseThrow().getStatus().name())
                .isEqualTo("RELEASED");
    }

    @Test
    void releaseAfterConfirmReturnsFalse() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        request("billing.reserveQuota", new ReserveRequest(generationId, userId), ReserveResponse.class);
        request("billing.confirmQuota", new ReserveRequest(generationId, userId), OkResponse.class);

        OkResponse response = request("billing.releaseQuota",
                new ReserveRequest(generationId, userId), OkResponse.class);

        assertThat(response.ok()).isFalse();
    }

    private <T> T request(String subject, Object payload, Class<T> responseType) throws Exception {
        byte[] data = objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        Dispatcher dispatcher = clientConnection.createDispatcher(msg -> future.complete(msg.getData()));
        String inbox = clientConnection.createInbox();
        dispatcher.subscribe(inbox);
        clientConnection.publish(subject, inbox, data);
        byte[] responseBytes = future.get(5, TimeUnit.SECONDS);
        clientConnection.closeDispatcher(dispatcher);
        return objectMapper.readValue(responseBytes, responseType);
    }
}