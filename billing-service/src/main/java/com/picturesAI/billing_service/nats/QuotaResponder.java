package com.picturesAI.billing_service.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.billing_service.nats.dto.OkResponse;
import com.picturesAI.billing_service.nats.dto.ReserveRequest;
import com.picturesAI.billing_service.nats.dto.ReserveResponse;
import com.picturesAI.billing_service.service.QuotaService;
import com.picturesAI.billing_service.service.dto.ConfirmResult;
import com.picturesAI.billing_service.service.dto.ReleaseResult;
import com.picturesAI.billing_service.service.dto.ReserveResult;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.nio.charset.StandardCharsets;

public class QuotaResponder implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(QuotaResponder.class);

    private final Connection connection;
    private final ObjectMapper objectMapper;
    private final QuotaService quotaService;
    private final NatsProperties properties;

    private Dispatcher dispatcher;
    private volatile boolean running;

    public QuotaResponder(Connection connection,
                          ObjectMapper objectMapper,
                          QuotaService quotaService,
                          NatsProperties properties) {
        this.connection = connection;
        this.objectMapper = objectMapper;
        this.quotaService = quotaService;
        this.properties = properties;
    }

    @Override
    public void start() {
        dispatcher = connection.createDispatcher(this::dispatch);
        dispatcher.subscribe(properties.getSubjects().getReserveQuota());
        dispatcher.subscribe(properties.getSubjects().getConfirmQuota());
        dispatcher.subscribe(properties.getSubjects().getReleaseQuota());
        running = true;
        log.info("QuotaResponder started, subjects: {} {} {}",
                properties.getSubjects().getReserveQuota(),
                properties.getSubjects().getConfirmQuota(),
                properties.getSubjects().getReleaseQuota());
    }

    @Override
    public void stop() {
        running = false;
        if (dispatcher != null) {
            try {
                connection.closeDispatcher(dispatcher);
            } catch (Exception e) {
                log.warn("Failed to close NATS dispatcher", e);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    private void dispatch(Message message) {
        String subject = message.getSubject();
        String replyTo = message.getReplyTo();
        if (replyTo == null) {
            log.warn("Received message without replyTo on subject {}", subject);
            return;
        }
        try {
            if (subject.equals(properties.getSubjects().getReserveQuota())) {
                handleReserve(message, replyTo);
            } else if (subject.equals(properties.getSubjects().getConfirmQuota())) {
                handleConfirm(message, replyTo);
            } else if (subject.equals(properties.getSubjects().getReleaseQuota())) {
                handleRelease(message, replyTo);
            } else {
                log.warn("Unknown subject {}", subject);
            }
        } catch (Exception e) {
            log.error("Error handling NATS request on subject {}: {}", subject, e.getMessage(), e);
        }
    }

    private void handleReserve(Message message, String replyTo) throws Exception {
        ReserveRequest request = objectMapper.readValue(message.getData(), ReserveRequest.class);
        ReserveResult result = quotaService.reserve(request.userId(), request.generationId());
        publish(replyTo, new ReserveResponse(result.allowed(), result.remaining()));
    }

    private void handleConfirm(Message message, String replyTo) throws Exception {
        ReserveRequest request = objectMapper.readValue(message.getData(), ReserveRequest.class);
        ConfirmResult result = quotaService.confirm(request.generationId());
        publish(replyTo, new OkResponse(result.ok()));
    }

    private void handleRelease(Message message, String replyTo) throws Exception {
        ReserveRequest request = objectMapper.readValue(message.getData(), ReserveRequest.class);
        ReleaseResult result = quotaService.release(request.generationId());
        publish(replyTo, new OkResponse(result.ok()));
    }

    private void publish(String replyTo, Object payload) throws Exception {
        byte[] bytes = objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        connection.publish(replyTo, bytes);
    }
}