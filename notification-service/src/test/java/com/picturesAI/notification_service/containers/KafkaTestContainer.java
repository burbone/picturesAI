package com.picturesAI.notification_service.containers;

import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public class KafkaTestContainer extends KafkaContainer {

    public KafkaTestContainer() {
        super(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    }
}