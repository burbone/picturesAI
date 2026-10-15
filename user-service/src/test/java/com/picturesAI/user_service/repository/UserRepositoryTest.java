package com.picturesAI.user_service.repository;

import com.picturesAI.user_service.AbstractPostgresTest;
import com.picturesAI.user_service.domain.Plan;
import com.picturesAI.user_service.domain.Status;
import com.picturesAI.user_service.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRepositoryTest extends AbstractPostgresTest {

    @Autowired
    private UserRepository userRepository;

    private User newUser(UUID keycloakId, String username) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setKeycloakId(keycloakId);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPlan(Plan.FREE);
        user.setStatus(Status.ACTIVE);
        return user;
    }

    @Test
    @Transactional
    void savesAndFindsByKeycloakId() {
        UUID keycloakId = UUID.randomUUID();
        User saved = userRepository.saveAndFlush(newUser(keycloakId, "alice"));

        Optional<User> found = userRepository.findByKeycloakId(keycloakId);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getUsername()).isEqualTo("alice");
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
        assertThat(found.get().getPlan()).isEqualTo(Plan.FREE);
        assertThat(found.get().getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(found.get().isPublic()).isTrue();
    }

    @Test
    @Transactional
    void keycloakIdIsUnique() {
        UUID keycloakId = UUID.randomUUID();
        userRepository.saveAndFlush(newUser(keycloakId, "alice"));

        assertThatThrownBy(() ->
                userRepository.saveAndFlush(newUser(keycloakId, "bob")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void usernameIsUnique() {
        userRepository.saveAndFlush(newUser(UUID.randomUUID(), "alice"));

        assertThatThrownBy(() ->
                userRepository.saveAndFlush(newUser(UUID.randomUUID(), "alice")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}