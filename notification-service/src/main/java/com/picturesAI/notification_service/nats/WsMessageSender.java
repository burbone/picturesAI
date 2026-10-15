package com.picturesAI.notification_service.nats;

import com.picturesAI.notification_service.ws.WebSocketSessionAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Component
public class WsMessageSender {

    private static final Logger log = LoggerFactory.getLogger(WsMessageSender.class);

    @SuppressWarnings("unchecked")
    public Mono<Void> sendText(WebSocketSession session, String json) {
        Object sink = session.getAttributes().get(WebSocketSessionAttributes.OUTBOUND_SINK);
        if (!(sink instanceof Sinks.Many<?> rawSink)) {
            log.warn("Outbound sink missing on session {}", session.getId());
            return Mono.empty();
        }
        Sinks.Many<WebSocketMessage> outbound = (Sinks.Many<WebSocketMessage>) rawSink;
        Sinks.EmitResult result = outbound.tryEmitNext(session.textMessage(json));
        if (result.isFailure()) {
            log.warn("Sink emit failed for session {}: {}", session.getId(), result);
            return Mono.error(new IllegalStateException("Sink emit failed: " + result));
        }
        return Mono.empty();
    }
}