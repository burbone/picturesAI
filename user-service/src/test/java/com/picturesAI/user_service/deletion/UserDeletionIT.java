package com.picturesAI.user_service.deletion;

import com.picturesAI.user_service.AbstractKafkaTest;
import com.picturesAI.user_service.domain.OutboxEvent;
import com.picturesAI.user_service.domain.Status;
import com.picturesAI.user_service.domain.User;
import com.picturesAI.user_service.keycloak.KeycloakAdminClient;
import com.picturesAI.user_service.repository.OutboxRepository;
import com.picturesAI.user_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class UserDeletionIT extends AbstractKafkaTest {

    @Autowired
    private UserDeletionService userDeletionService;

    @Autowired
    private UserDeletionWorker userDeletionWorker;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @MockitoBean
    private KeycloakAdminClient keycloakAdminClient;

    @BeforeEach
    void cleanUp() {
        outboxRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User activeUser(UUID keycloakId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setKeycloakId(keycloakId);
        u.setUsername("alice-" + keycloakId.toString().substring(0, 6));
        u.setStatus(Status.ACTIVE);
        return userRepository.saveAndFlush(u);
    }

    @Test
    void initiateMarksDeletingAndWritesSingleOutbox() {
        User user = activeUser(UUID.randomUUID());

        userDeletionService.initiate(user.getKeycloakId());

        User refreshed = userRepository.findById(user.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(Status.DELETING);
        List<OutboxEvent> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("user.deleted");
        assertThat(events.get(0).getAggregateId()).isEqualTo(user.getId());
    }

    @Test
    void repeatedInitiateDoesNotWriteSecondOutbox() {
        User user = activeUser(UUID.randomUUID());

        userDeletionService.initiate(user.getKeycloakId());
        userDeletionService.initiate(user.getKeycloakId());

        assertThat(outboxRepository.findAll()).hasSize(1);
    }

    @Test
    void workerDeletesRowWhenKeycloakSucceeds() {
        User user = activeUser(UUID.randomUUID());
        userDeletionService.initiate(user.getKeycloakId());

        userDeletionWorker.processPending();

        verify(keycloakAdminClient, times(1)).deleteUser(user.getKeycloakId());
        assertThat(userRepository.findById(user.getId())).isEmpty();
    }

    @Test
    void workerKeepsRowWhenKeycloakFails() {
        User user = activeUser(UUID.randomUUID());
        userDeletionService.initiate(user.getKeycloakId());
        doThrow(new RuntimeException("keycloak down"))
                .when(keycloakAdminClient).deleteUser(user.getKeycloakId());

        userDeletionWorker.processPending();

        User refreshed = userRepository.findById(user.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(Status.DELETING);
    }

    @Test
    void workerSkipsNonDeletingUsers() {
        activeUser(UUID.randomUUID());

        userDeletionWorker.processPending();

        verify(keycloakAdminClient, never()).deleteUser(any());
    }
}