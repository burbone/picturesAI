package com.picturesAI.notification_service;

import com.picturesAI.notification_service.integration.GenerationServiceClient;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {NotificationServiceApplication.class, AbstractIntegrationTest.NatsMockConfig.class}
)
public abstract class AbstractIntegrationTest {

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    GenerationServiceClient generationServiceClient;

    @TestConfiguration
    public static class NatsMockConfig {

        @Bean
        @Primary
        Connection mockNatsConnection() {
            Connection connection = mock(Connection.class);
            Dispatcher dispatcher = mock(Dispatcher.class);
            when(connection.createDispatcher(any())).thenReturn(dispatcher);
            Mockito.lenient().when(connection.getConnectedUrl()).thenReturn("nats://mock");
            return connection;
        }
    }
}