package com.picturesAI.notification_service.config;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(NatsProperties.class)
public class NatsConfig {

    @Bean(destroyMethod = "close")
    public Connection natsConnection(NatsProperties props) throws IOException, InterruptedException {
        Options options = new Options.Builder()
                .server(props.url())
                .userInfo(props.username(), props.password())
                .connectionTimeout(Duration.ofSeconds(5))
                .reconnectWait(Duration.ofSeconds(2))
                .maxReconnects(-1)
                .build();
        return Nats.connect(options);
    }
}