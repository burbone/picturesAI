package com.picturesAI.generation_service.e2e;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.mockito.Mockito.mock;

@TestConfiguration
@Profile("e2e")
public class E2EConfig {

    @Bean
    @Primary
    JwtDecoder jwtDecoder() {
        return mock(JwtDecoder.class);
    }
}