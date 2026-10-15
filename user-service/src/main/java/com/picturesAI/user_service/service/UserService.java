package com.picturesAI.user_service.service;

import com.picturesAI.user_service.deletion.UserDeletionService;
import com.picturesAI.user_service.domain.Status;
import com.picturesAI.user_service.domain.User;
import com.picturesAI.user_service.dto.UpdateUserRequest;
import com.picturesAI.user_service.dto.UserResponse;
import com.picturesAI.user_service.mapper.UserMapper;
import com.picturesAI.user_service.repository.UserRepository;
import com.picturesAI.user_service.security.CurrentUserProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserCreationService userCreationService;
    private final UserDeletionService userDeletionService;
    private final CurrentUserProvider currentUser;
    private final UserMapper userMapper;

    public UserService(UserRepository userRepository,
                       UserCreationService userCreationService,
                       UserDeletionService userDeletionService,
                       CurrentUserProvider currentUser,
                       UserMapper userMapper) {
        this.userRepository = userRepository;
        this.userCreationService = userCreationService;
        this.userDeletionService = userDeletionService;
        this.currentUser = currentUser;
        this.userMapper = userMapper;
    }

    @Transactional
    public UserResponse getOrCreateCurrent() {
        UUID keycloakId = currentUser.userId();
        User existing = userRepository.findByKeycloakId(keycloakId).orElse(null);
        if (existing != null) {
            return userMapper.toResponse(existing);
        }
        String username = currentUser.username();
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("missing username claim");
        }
        try {
            User created = userCreationService.create(keycloakId, username);
            return userMapper.toResponse(created);
        } catch (DataIntegrityViolationException race) {
            User winner = userRepository.findByKeycloakId(keycloakId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Race lost but row not found for keycloakId=" + keycloakId, race));
            return userMapper.toResponse(winner);
        }
    }

    @Transactional
    public UserResponse updateCurrent(UpdateUserRequest request) {
        UUID keycloakId = currentUser.userId();
        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new UserNotFoundException("Profile not found"));
        if (user.getStatus() == Status.DELETING) {
            throw new UserInDeletionException("Profile is being deleted");
        }
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl());
        }
        if (request.bio() != null) {
            user.setBio(request.bio());
        }
        if (request.isPublic() != null) {
            user.setPublic(request.isPublic());
        }
        return userMapper.toResponse(userRepository.saveAndFlush(user));
    }

    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        if (user.getStatus() == Status.DELETING) {
            throw new UserNotFoundException("User not found");
        }
        if (!user.isPublic()) {
            UUID currentKeycloakId = currentUser.userId();
            if (!user.getKeycloakId().equals(currentKeycloakId)) {
                throw new UserNotFoundException("User not found");
            }
        }
        return userMapper.toResponse(user);
    }

    public void deleteCurrent() {
        userDeletionService.initiate(currentUser.userId());
    }
}