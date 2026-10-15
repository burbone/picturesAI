package com.picturesAI.generation_service.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.config.SecurityConfig;
import com.picturesAI.generation_service.domain.GenerationStatus;
import com.picturesAI.generation_service.domain.Tier;
import com.picturesAI.generation_service.security.CurrentUserProvider;
import com.picturesAI.generation_service.service.CancelResult;
import com.picturesAI.generation_service.service.CancellationService;
import com.picturesAI.generation_service.service.CreateGenerationResult;
import com.picturesAI.generation_service.service.GenerationCreationService;
import com.picturesAI.generation_service.service.GenerationQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GenerationController.class)
@Import({
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        GenerationControllerTest.MockUserConfig.class
})
class GenerationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    GenerationCreationService creationService;

    @MockitoBean
    CancellationService cancellationService;

    @MockitoBean
    GenerationQueryService queryService;

    @Test
    void returns202AndBodyOnCreate() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        when(creationService.create(any(), eq("idem-1"), any(), any(Tier.class)))
                .thenReturn(new CreateGenerationResult.Created(List.of(jobId), batchId, Tier.FREE));

        String body = """
                {"prompt":"a cat","model":"sd-xl","size":"512x512","count":1}
                """;

        mockMvc.perform(post("/api/v1/generations")
                        .header("Idempotency-Key", "idem-1")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.batchId").value(batchId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.tier").value("FREE"))
                .andExpect(jsonPath("$.statusUrl").value("/api/v1/generations/" + jobId));
    }

    @Test
    void returns409WithJobIdOnDuplicate() throws Exception {
        UUID existingJob = UUID.randomUUID();
        when(creationService.create(any(), anyString(), any(), any(Tier.class)))
                .thenReturn(new CreateGenerationResult.Duplicate(existingJob));

        mockMvc.perform(post("/api/v1/generations")
                        .header("Idempotency-Key", "idem-dup")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"p\",\"model\":\"m\",\"size\":\"512x512\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("duplicate"))
                .andExpect(jsonPath("$.jobId").value(existingJob.toString()));
    }

    @Test
    void returns402OnQuotaDenied() throws Exception {
        when(creationService.create(any(), anyString(), any(), any(Tier.class)))
                .thenReturn(new CreateGenerationResult.QuotaDenied());

        mockMvc.perform(post("/api/v1/generations")
                        .header("Idempotency-Key", "idem-q")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"p\",\"model\":\"m\",\"size\":\"512x512\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value("quota_exceeded"));
    }

    @Test
    void returns422OnValidationFailure() throws Exception {
        mockMvc.perform(post("/api/v1/generations")
                        .header("Idempotency-Key", "idem-v")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"\",\"model\":\"m\",\"size\":\"512x512\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void returns400WhenIdempotencyHeaderMissing() throws Exception {
        mockMvc.perform(post("/api/v1/generations")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"p\",\"model\":\"m\",\"size\":\"512x512\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_header"));
    }

    @Test
    void cancelReturns202WhenCancelledImmediately() throws Exception {
        when(cancellationService.cancel(any(), any())).thenReturn(new CancelResult.Cancelled());

        mockMvc.perform(delete("/api/v1/generations/{id}", UUID.randomUUID())
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelReturns202WhenDeferred() throws Exception {
        when(cancellationService.cancel(any(), any())).thenReturn(new CancelResult.Accepted());

        mockMvc.perform(delete("/api/v1/generations/{id}", UUID.randomUUID())
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CANCELLING"));
    }

    @Test
    void cancelReturns404WhenNotFound() throws Exception {
        when(cancellationService.cancel(any(), any())).thenReturn(new CancelResult.NotFound());

        mockMvc.perform(delete("/api/v1/generations/{id}", UUID.randomUUID())
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void cancelReturns403WhenNotOwner() throws Exception {
        when(cancellationService.cancel(any(), any())).thenReturn(new CancelResult.Forbidden());

        mockMvc.perform(delete("/api/v1/generations/{id}", UUID.randomUUID())
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void cancelReturns409WhenTerminal() throws Exception {
        when(cancellationService.cancel(any(), any()))
                .thenReturn(new CancelResult.AlreadyTerminal(GenerationStatus.COMPLETED));

        mockMvc.perform(delete("/api/v1/generations/{id}", UUID.randomUUID())
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("already_terminal"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
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