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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(excludeAutoConfiguration = OAuth2ResourceServerAutoConfiguration.class)
@Import(SecurityConfig.class)
class SecurityConfigNonDevIT {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    GenerationCreationService generationCreationService;

    @MockitoBean
    CancellationService cancellationService;

    @MockitoBean
    GenerationQueryService generationQueryService;

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Test
    void apiIsPermittedWithoutJwt() throws Exception {
        mockMvc.perform(get("/api/v1/generations/anything"))
                .andExpect(status().is(not(401)));
    }
}