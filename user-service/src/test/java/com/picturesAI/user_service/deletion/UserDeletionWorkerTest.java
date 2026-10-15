package com.picturesAI.user_service.deletion;

import com.picturesAI.user_service.domain.Plan;
import com.picturesAI.user_service.domain.Status;
import com.picturesAI.user_service.domain.User;
import com.picturesAI.user_service.keycloak.KeycloakAdminClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDeletionWorkerTest {

    @Mock
    private UserDeletionService userDeletionService;

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    private SimpleMeterRegistry registry;
    private UserDeletionWorker worker;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        UserDeletionProperties properties = new UserDeletionProperties();
        properties.setBatchSize(10);
        worker = new UserDeletionWorker(userDeletionService, keycloakAdminClient, properties, registry);
    }

    private User deleting(UUID id, UUID keycloakId) {
        User u = new User();
        u.setId(id);
        u.setKeycloakId(keycloakId);
        u.setUsername("alice");
        u.setPlan(Plan.FREE);
        u.setStatus(Status.DELETING);
        return u;
    }

    @Test
    void processesClaimedUserAndDeletesRow() {
        User a = deleting(UUID.randomUUID(), UUID.randomUUID());
        when(userDeletionService.listPendingDeletions(anyInt())).thenReturn(List.of(a));
        when(userDeletionService.markAttempt(a.getId())).thenReturn(true);
        when(userDeletionService.countPending()).thenReturn(0L);

        worker.processPending();

        verify(keycloakAdminClient).deleteUser(a.getKeycloakId());
        verify(userDeletionService).finalizeDeletion(a.getId());
        assertThat(registry.get("user_deletion_failed_total").counter().count()).isEqualTo(0.0);
        assertThat(registry.get("user_deletion_duration_seconds").timer().count()).isEqualTo(1L);
        assertThat(registry.get("user_deletion_pending_total").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void keepsRowWhenKeycloakFailsAndIncrementsCounter() {
        User a = deleting(UUID.randomUUID(), UUID.randomUUID());
        when(userDeletionService.listPendingDeletions(anyInt())).thenReturn(List.of(a));
        when(userDeletionService.markAttempt(a.getId())).thenReturn(true);
        when(userDeletionService.countPending()).thenReturn(1L);
        doThrow(new RuntimeException("keycloak down"))
                .when(keycloakAdminClient).deleteUser(a.getKeycloakId());

        worker.processPending();

        verify(userDeletionService, never()).finalizeDeletion(a.getId());
        assertThat(registry.get("user_deletion_failed_total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("user_deletion_pending_total").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void skipsUserWhenClaimFails() {
        User a = deleting(UUID.randomUUID(), UUID.randomUUID());
        when(userDeletionService.listPendingDeletions(anyInt())).thenReturn(List.of(a));
        when(userDeletionService.markAttempt(a.getId())).thenReturn(false);
        when(userDeletionService.countPending()).thenReturn(0L);

        worker.processPending();

        verify(keycloakAdminClient, never()).deleteUser(any());
        verify(userDeletionService, never()).finalizeDeletion(any());
    }

    @Test
    void continuesAfterFailure() {
        User a = deleting(UUID.randomUUID(), UUID.randomUUID());
        User b = deleting(UUID.randomUUID(), UUID.randomUUID());
        when(userDeletionService.listPendingDeletions(anyInt())).thenReturn(List.of(a, b));
        when(userDeletionService.markAttempt(a.getId())).thenReturn(true);
        when(userDeletionService.markAttempt(b.getId())).thenReturn(true);
        when(userDeletionService.countPending()).thenReturn(1L);
        doThrow(new RuntimeException("keycloak down"))
                .when(keycloakAdminClient).deleteUser(a.getKeycloakId());

        worker.processPending();

        verify(userDeletionService, never()).finalizeDeletion(a.getId());
        verify(keycloakAdminClient, times(1)).deleteUser(b.getKeycloakId());
        verify(userDeletionService, times(1)).finalizeDeletion(b.getId());
    }
}
