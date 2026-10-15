package com.picturesAI.generation_service.config;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.time.Duration;

@Configuration
@Profile("!dev")
public class NatsConfig {

    @Bean(destroyMethod = "close")
    public Connection natsConnection(
            @Value("${nats.url}") String url,
            @Value("${nats.username}") String username,
            @Value("${nats.password}") String password) throws IOException, InterruptedException {
        Options options = new Options.Builder()
                .server(url)
                .userInfo(username, password)
                .maxReconnects(-1)
                .connectionTimeout(Duration.ofSeconds(5))
                .build();
        return Nats.connect(options);
    }
}
