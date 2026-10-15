package com.picturesAI.user_service.deletion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.user_service.domain.OutboxEvent;
import com.picturesAI.user_service.domain.Plan;
import com.picturesAI.user_service.domain.Status;
import com.picturesAI.user_service.domain.User;
import com.picturesAI.user_service.repository.OutboxRepository;
import com.picturesAI.user_service.repository.UserRepository;
import com.picturesAI.user_service.service.UserNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDeletionServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OutboxRepository outboxRepository;

    private UserDeletionService service;

    @BeforeEach
    void setUp() {
        service = new UserDeletionService(userRepository, outboxRepository, new ObjectMapper());
    }

    private User user(UUID id, UUID keycloakId, Status status) {
        User u = new User();
        u.setId(id);
        u.setKeycloakId(keycloakId);
        u.setUsername("alice");
        u.setPlan(Plan.FREE);
        u.setStatus(status);
        return u;
    }

    @Test
    void initiateMarksDeletingAndWritesOutboxEvent() {
        UUID id = UUID.randomUUID();
        UUID keycloakId = UUID.randomUUID();
        User existing = user(id, keycloakId, Status.ACTIVE);
        when(userRepository.findByKeycloakId(keycloakId)).thenReturn(Optional.of(existing));
        when(userRepository.saveAndFlush(existing)).thenReturn(existing);

        service.initiate(keycloakId);

        assertThat(existing.getStatus()).isEqualTo(Status.DELETING);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, times(1)).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo("user.deleted");
        assertThat(event.getAggregateId()).isEqualTo(id);
        assertThat(event.getPayload()).contains(id.toString()).contains(keycloakId.toString());
    }

    @Test
    void initiateIsIdempotentWhenAlreadyDeleting() {
        UUID keycloakId = UUID.randomUUID();
        User existing = user(UUID.randomUUID(), keycloakId, Status.DELETING);
        when(userRepository.findByKeycloakId(keycloakId)).thenReturn(Optional.of(existing));

        service.initiate(keycloakId);

        verify(userRepository, never()).saveAndFlush(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void initiateUnknownProfileThrows() {
        UUID keycloakId = UUID.randomUUID();
        when(userRepository.findByKeycloakId(keycloakId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiate(keycloakId))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void listPendingDeletionsDelegatesToRepository() {
        User d1 = user(UUID.randomUUID(), UUID.randomUUID(), Status.DELETING);
        when(userRepository.findByStatus(eq(Status.DELETING), any())).thenReturn(List.of(d1));

        List<User> result = service.listPendingDeletions(50);

        assertThat(result).containsExactly(d1);
    }

    @Test
    void markAttemptReturnsTrueWhenRowUpdated() {
        UUID id = UUID.randomUUID();
        when(userRepository.touchForDeletion(eq(id), eq(Status.DELETING), any(Instant.class)))
                .thenReturn(1);

        assertThat(service.markAttempt(id)).isTrue();
    }

    @Test
    void markAttemptReturnsFalseWhenNothingUpdated() {
        UUID id = UUID.randomUUID();
        when(userRepository.touchForDeletion(eq(id), eq(Status.DELETING), any(Instant.class)))
                .thenReturn(0);

        assertThat(service.markAttempt(id)).isFalse();
    }

    @Test
    void finalizeDeletionDeletesExistingRow() {
        UUID id = UUID.randomUUID();
        when(userRepository.existsById(id)).thenReturn(true);

        service.finalizeDeletion(id);

        verify(userRepository, times(1)).deleteById(id);
    }

    @Test
    void finalizeDeletionIsNoopWhenAlreadyGone() {
        UUID id = UUID.randomUUID();
        when(userRepository.existsById(id)).thenReturn(false);

        service.finalizeDeletion(id);

        verify(userRepository, never()).deleteById(any());
    }
}