package com.picturesAI.user_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.user_service.domain.OutboxEvent;
import com.picturesAI.user_service.domain.Plan;
import com.picturesAI.user_service.domain.Status;
import com.picturesAI.user_service.domain.User;
import com.picturesAI.user_service.repository.OutboxRepository;
import com.picturesAI.user_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserCreationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OutboxRepository outboxRepository;

    private UserCreationService service;

    @BeforeEach
    void setUp() {
        service = new UserCreationService(userRepository, outboxRepository, new ObjectMapper());
    }

    private User stubSaved(User u) {
        u.setPlan(Plan.FREE);
        u.setStatus(Status.ACTIVE);
        try {
            java.lang.reflect.Field f = User.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(u, Instant.now());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return u;
    }

    @Test
    void createPersistsUserAndOutboxEvent() {
        UUID keycloakId = UUID.randomUUID();
        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(inv -> stubSaved(inv.getArgument(0)));

        User created = service.create(keycloakId, "alice");

        assertThat(created.getUsername()).isEqualTo("alice");
        assertThat(created.getDisplayName()).isEqualTo("alice");
        assertThat(created.getKeycloakId()).isEqualTo(keycloakId);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, times(1)).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo("user.created");
        assertThat(event.getAggregateId()).isEqualTo(created.getId());
        assertThat(event.getPayload())
                .contains("\"userId\":\"" + created.getId())
                .contains("\"username\":\"alice\"");
    }

    @Test
    void createUsesUsernameAsIs() {
        UUID keycloakId = UUID.randomUUID();
        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(inv -> stubSaved(inv.getArgument(0)));

        User created = service.create(keycloakId, "Vasya Pupkin");

        assertThat(created.getUsername()).isEqualTo("Vasya Pupkin");
        assertThat(created.getDisplayName()).isEqualTo("Vasya Pupkin");
    }
}