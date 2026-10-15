package com.picturesAI.tagging_service.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class UserDeletedConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserDeletedConsumer.class);

    @KafkaListener(topics = "user.deleted", groupId = "tagging-service")
    public void onUserDeleted(String payload, Acknowledgment ack) {
        log.info("user.deleted event received, no action required: {}", payload);
        ack.acknowledge();
    }
}