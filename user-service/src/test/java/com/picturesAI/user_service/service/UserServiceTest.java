package com.picturesAI.user_service.service;

import com.picturesAI.user_service.deletion.UserDeletionService;
import com.picturesAI.user_service.domain.Plan;
import com.picturesAI.user_service.domain.Status;
import com.picturesAI.user_service.domain.User;
import com.picturesAI.user_service.dto.UpdateUserRequest;
import com.picturesAI.user_service.dto.UserResponse;
import com.picturesAI.user_service.mapper.UserMapper;
import com.picturesAI.user_service.repository.UserRepository;
import com.picturesAI.user_service.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserCreationService userCreationService;

    @Mock
    private UserDeletionService userDeletionService;

    @Mock
    private CurrentUserProvider currentUser;

    private final UserMapper userMapper = Mappers.getMapper(UserMapper.class);

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, userCreationService, userDeletionService,
                currentUser, userMapper);
    }

    private User user(UUID id, UUID keycloakId, Status status, boolean isPublic) {
        User u = new User();
        u.setId(id);
        u.setKeycloakId(keycloakId);
        u.setUsername("alice");
        u.setDisplayName("Alice");
        u.setPlan(Plan.FREE);
        u.setStatus(status);
        u.setPublic(isPublic);
        return u;
    }

    @Test
    void getOrCreateReturnsExisting() {
        UUID keycloakId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(currentUser.userId()).thenReturn(keycloakId);
        when(userRepository.findByKeycloakId(keycloakId))
                .thenReturn(Optional.of(user(id, keycloakId, Status.ACTIVE, true)));

        UserResponse response = userService.getOrCreateCurrent();

        assertThat(response.id()).isEqualTo(id);
        verify(userCreationService, never()).create(any(), any());
    }

    @Test
    void getOrCreateReturnsDeletingProfile() {
        UUID keycloakId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(currentUser.userId()).thenReturn(keycloakId);
        when(userRepository.findByKeycloakId(keycloakId))
                .thenReturn(Optional.of(user(id, keycloakId, Status.DELETING, true)));

        UserResponse response = userService.getOrCreateCurrent();

        assertThat(response.status()).isEqualTo(Status.DELETING);
    }

    @Test
    void getOrCreateCreatesWhenUsernamePresent() {
        UUID keycloakId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(currentUser.userId()).thenReturn(keycloakId);
        when(currentUser.username()).thenReturn("alice");
        when(userRepository.findByKeycloakId(keycloakId)).thenReturn(Optional.empty());
        when(userCreationService.create(keycloakId, "alice"))
                .thenReturn(user(id, keycloakId, Status.ACTIVE, true));

        UserResponse response = userService.getOrCreateCurrent();

        assertThat(response.id()).isEqualTo(id);
        verify(userCreationService, times(1)).create(eq(keycloakId), eq("alice"));
    }

    @Test
    void getOrCreateWithoutUsernameThrows() {
        UUID keycloakId = UUID.randomUUID();
        when(currentUser.userId()).thenReturn(keycloakId);
        when(currentUser.username()).thenReturn(null);
        when(userRepository.findByKeycloakId(keycloakId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getOrCreateCurrent())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("missing username claim");
        verify(userCreationService, never()).create(any(), any());
    }

    @Test
    void getOrCreateHandlesRaceByRefetching() {
        UUID keycloakId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(currentUser.userId()).thenReturn(keycloakId);
        when(currentUser.username()).thenReturn("alice");
        when(userRepository.findByKeycloakId(keycloakId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(user(id, keycloakId, Status.ACTIVE, true)));
        when(userCreationService.create(keycloakId, "alice"))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        UserResponse response = userService.getOrCreateCurrent();

        assertThat(response.id()).isEqualTo(id);
        verify(userRepository, times(2)).findByKeycloakId(keycloakId);
    }

    @Test
    void updateAppliesNonNullFields() {
        UUID keycloakId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        User existing = user(id, keycloakId, Status.ACTIVE, true);
        when(currentUser.userId()).thenReturn(keycloakId);
        when(userRepository.findByKeycloakId(keycloakId)).thenReturn(Optional.of(existing));
        when(userRepository.saveAndFlush(existing)).thenReturn(existing);

        UserResponse response = userService.updateCurrent(
                new UpdateUserRequest("New Name", null, "bio", false));

        assertThat(response.displayName()).isEqualTo("New Name");
        assertThat(response.bio()).isEqualTo("bio");
        assertThat(response.isPublic()).isFalse();
        assertThat(response.avatarUrl()).isNull();
    }

    @Test
    void updateDeletingProfileThrowsConflict() {
        UUID keycloakId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(currentUser.userId()).thenReturn(keycloakId);
        when(userRepository.findByKeycloakId(keycloakId))
                .thenReturn(Optional.of(user(id, keycloakId, Status.DELETING, true)));

        assertThatThrownBy(() -> userService.updateCurrent(
                new UpdateUserRequest("x", null, null, null)))
                .isInstanceOf(UserInDeletionException.class);
    }

    @Test
    void updateUnknownProfileThrows() {
        UUID keycloakId = UUID.randomUUID();
        when(currentUser.userId()).thenReturn(keycloakId);
        when(userRepository.findByKeycloakId(keycloakId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateCurrent(
                new UpdateUserRequest("x", null, null, null)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void getByIdReturnsPublicProfile() {
        UUID id = UUID.randomUUID();
        User publicUser = user(id, UUID.randomUUID(), Status.ACTIVE, true);
        when(userRepository.findById(id)).thenReturn(Optional.of(publicUser));

        UserResponse response = userService.getById(id);

        assertThat(response.id()).isEqualTo(id);
    }

    @Test
    void getByIdHidesDeletingProfile() {
        UUID id = UUID.randomUUID();
        User deleting = user(id, UUID.randomUUID(), Status.DELETING, true);
        when(userRepository.findById(id)).thenReturn(Optional.of(deleting));

        assertThatThrownBy(() -> userService.getById(id))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void getByIdHidesForeignPrivateProfile() {
        UUID id = UUID.randomUUID();
        User privateUser = user(id, UUID.randomUUID(), Status.ACTIVE, false);
        when(userRepository.findById(id)).thenReturn(Optional.of(privateUser));
        when(currentUser.userId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> userService.getById(id))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void getByIdAllowsOwnerOfPrivateProfile() {
        UUID id = UUID.randomUUID();
        UUID keycloakId = UUID.randomUUID();
        User privateUser = user(id, keycloakId, Status.ACTIVE, false);
        when(userRepository.findById(id)).thenReturn(Optional.of(privateUser));
        when(currentUser.userId()).thenReturn(keycloakId);

        UserResponse response = userService.getById(id);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.isPublic()).isFalse();
    }

    @Test
    void deleteCurrentDelegatesToDeletionService() {
        UUID keycloakId = UUID.randomUUID();
        when(currentUser.userId()).thenReturn(keycloakId);

        userService.deleteCurrent();

        verify(userDeletionService, times(1)).initiate(keycloakId);
    }
}