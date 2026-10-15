package com.picturesAI.user_service.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.user_service.domain.Plan;
import com.picturesAI.user_service.domain.Status;
import com.picturesAI.user_service.dto.UpdateUserRequest;
import com.picturesAI.user_service.dto.UserResponse;
import com.picturesAI.user_service.security.SecurityConfig;
import com.picturesAI.user_service.security.UnauthorizedException;
import com.picturesAI.user_service.service.UserInDeletionException;
import com.picturesAI.user_service.service.UserNotFoundException;
import com.picturesAI.user_service.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class UserControllerWebTest {

    @TestConfiguration
    static class Config {

        @Bean
        @Primary
        TestIdentity testIdentity() {
            return new TestIdentity();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    private UUID userId;
    private UserResponse sample;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        Instant now = Instant.now();
        sample = new UserResponse(
                userId,
                UUID.randomUUID(),
                "alice",
                "Alice",
                null,
                null,
                true,
                Plan.FREE,
                Status.ACTIVE,
                now,
                now);
    }

    @Test
    void getMeReturnsProfile() throws Exception {
        when(userService.getOrCreateCurrent()).thenReturn(sample);

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.isPublic").value(true))
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getMeReturnsDeletingStatus() throws Exception {
        UserResponse deleting = new UserResponse(
                userId, sample.keycloakId(), "alice", "Alice", null, null, true,
                Plan.FREE, Status.DELETING, sample.createdAt(), sample.updatedAt());
        when(userService.getOrCreateCurrent()).thenReturn(deleting);

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETING"));
    }

    @Test
    void getMeWithoutIdentityReturns401() throws Exception {
        when(userService.getOrCreateCurrent())
                .thenThrow(new UnauthorizedException("Missing X-User-Id"));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Missing X-User-Id"));
    }

    @Test
    void getMeMissingUsernameReturns500() throws Exception {
        when(userService.getOrCreateCurrent())
                .thenThrow(new IllegalStateException("missing username claim"));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("missing username claim"));
    }

    @Test
    void patchMeReturnsUpdatedProfile() throws Exception {
        UserResponse updated = new UserResponse(
                userId, sample.keycloakId(), "alice", "New Name",
                "https://cdn/a.png", "bio", false,
                Plan.FREE, Status.ACTIVE, sample.createdAt(), Instant.now());
        when(userService.updateCurrent(any(UpdateUserRequest.class))).thenReturn(updated);

        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateUserRequest("New Name", "https://cdn/a.png", "bio", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("New Name"))
                .andExpect(jsonPath("$.avatarUrl").value("https://cdn/a.png"))
                .andExpect(jsonPath("$.bio").value("bio"))
                .andExpect(jsonPath("$.isPublic").value(false));
    }

    @Test
    void patchMeAfterDeleteReturns409() throws Exception {
        when(userService.updateCurrent(any(UpdateUserRequest.class)))
                .thenThrow(new UserInDeletionException("Profile is being deleted"));

        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateUserRequest("New Name", null, null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Profile is being deleted"));
    }

    @Test
    void patchMeWithTooLongDisplayNameReturns400() throws Exception {
        String tooLong = "x".repeat(101);

        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateUserRequest(tooLong, null, null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getByIdReturnsPublicProfile() throws Exception {
        when(userService.getById(eq(userId))).thenReturn(sample);

        mockMvc.perform(get("/api/v1/users/" + userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()));
    }

    @Test
    void getByIdForeignPrivateReturns404() throws Exception {
        when(userService.getById(eq(userId)))
                .thenThrow(new UserNotFoundException("User not found"));

        mockMvc.perform(get("/api/v1/users/" + userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    void getByIdDeletingReturns404() throws Exception {
        when(userService.getById(eq(userId)))
                .thenThrow(new UserNotFoundException("User not found"));

        mockMvc.perform(get("/api/v1/users/" + userId))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByIdInvalidUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/users/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generationsReturns501() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + UUID.randomUUID() + "/generations"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void deleteMeReturns202() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me"))
                .andExpect(status().isAccepted());

        verify(userService).deleteCurrent();
    }
}