package com.picturesAI.billing_service.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.billing_service.service.QuotaService;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

import java.io.IOException;

@Configuration
@Profile("!test")
@EnableConfigurationProperties(NatsProperties.class)
public class NatsConfig {

    @Bean(destroyMethod = "close")
    public Connection natsConnection(NatsProperties properties) throws IOException, InterruptedException {
        Options.Builder builder = new Options.Builder()
                .server(properties.getUrl())
                .maxReconnects(-1)
                .reconnectWait(java.time.Duration.ofSeconds(2))
                .connectionTimeout(java.time.Duration.ofSeconds(5));
        if (StringUtils.hasText(properties.getUser()) && StringUtils.hasText(properties.getPassword())) {
            builder.userInfo(properties.getUser().toCharArray(), properties.getPassword().toCharArray());
        }
        return Nats.connect(builder.build());
    }

    @Bean
    public QuotaResponder quotaResponder(Connection connection,
                                         ObjectMapper objectMapper,
                                         QuotaService quotaService,
                                         NatsProperties properties) {
        return new QuotaResponder(connection, objectMapper, quotaService, properties);
    }
}