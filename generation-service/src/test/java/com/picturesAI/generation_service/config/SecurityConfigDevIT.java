package com.picturesAI.generation_service.config;

import com.picturesAI.generation_service.security.CurrentUserProvider;
import com.picturesAI.generation_service.service.CancellationService;
import com.picturesAI.generation_service.service.GenerationCreationService;
import com.picturesAI.generation_service.service.GenerationQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(excludeAutoConfiguration = OAuth2ResourceServerAutoConfiguration.class)
@Import(SecurityConfig.class)
@ActiveProfiles("dev")
class SecurityConfigDevIT {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    GenerationCreationService generationCreationService;

    @MockitoBean
    CancellationService cancellationService;

    @MockitoBean
    GenerationQueryService generationQueryService;

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Test
    void apiRequiresJwt() throws Exception {
        mockMvc.perform(get("/api/v1/generations/anything"))
                .andExpect(status().isUnauthorized());
    }
}