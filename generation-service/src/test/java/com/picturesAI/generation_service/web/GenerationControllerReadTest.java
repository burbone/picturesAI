package com.picturesAI.generation_service.web;

import com.picturesAI.generation_service.config.SecurityConfig;
import com.picturesAI.generation_service.domain.GenerationStatus;
import com.picturesAI.generation_service.security.CurrentUserProvider;
import com.picturesAI.generation_service.service.CancellationService;
import com.picturesAI.generation_service.service.GenerationCreationService;
import com.picturesAI.generation_service.service.GenerationQueryService;
import com.picturesAI.generation_service.web.dto.GenerationPage;
import com.picturesAI.generation_service.web.dto.GenerationStatusView;
import com.picturesAI.generation_service.web.dto.GenerationView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GenerationController.class)
@Import({
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        GenerationControllerReadTest.MockUserConfig.class
})
class GenerationControllerReadTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    GenerationCreationService creationService;

    @MockitoBean
    CancellationService cancellationService;

    @MockitoBean
    GenerationQueryService queryService;

    @Test
    void getOneReturns200WhenOwned() throws Exception {
        UUID id = UUID.randomUUID();
        GenerationView view = new GenerationView(
                id, UUID.randomUUID(), "p", null, "m", "512x512",
                "FREE", "QUEUED", null, null, null, null, 0, false, null,
                Instant.now(), null, null, null);
        when(queryService.findById(any(), any())).thenReturn(Optional.of(view));

        mockMvc.perform(get("/api/v1/generations/{id}", id)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void getOneReturns404WhenMissing() throws Exception {
        when(queryService.findById(any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/generations/{id}", UUID.randomUUID())
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void getStatusReturns200WhenOwned() throws Exception {
        UUID id = UUID.randomUUID();
        GenerationStatusView view = new GenerationStatusView(
                id, "COMPLETED", "FREE", "http://img", "http://thumb",
                null, Instant.now(), Instant.now(), null);
        when(queryService.findStatus(any(), any())).thenReturn(Optional.of(view));

        mockMvc.perform(get("/api/v1/generations/{id}/status", id)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.imageUrl").value("http://img"));
    }

    @Test
    void getStatusReturns404WhenMissing() throws Exception {
        when(queryService.findStatus(any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/generations/{id}/status", UUID.randomUUID())
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isNotFound());
    }

    @Test
    void historyReturnsPage() throws Exception {
        UUID id = UUID.randomUUID();
        GenerationView view = new GenerationView(
                id, UUID.randomUUID(), "p", null, "m", "512x512",
                "FREE", "COMPLETED", null, null, null, null, 0, false, null,
                Instant.now(), null, null, null);
        GenerationPage page = new GenerationPage(List.of(view), id);
        when(queryService.history(any(), any(), anyInt())).thenReturn(page);

        mockMvc.perform(get("/api/v1/generations?limit=10")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").value(id.toString()));
    }

    @TestConfiguration
    static class MockUserConfig {

        @Bean
        @Primary
        CurrentUserProvider testCurrentUserProvider() {
            return new CurrentUserProvider() {
                @Override
                public UUID userId() {
                    return UUID.fromString("00000000-0000-0000-0000-000000000001");
                }

                @Override
                public Set<String> roles() {
                    return Set.of("ROLE_USER");
                }

                @Override
                public String username() {
                    return "test";
                }
            };
        }
    }
}