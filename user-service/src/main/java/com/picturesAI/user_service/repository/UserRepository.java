package com.picturesAI.user_service.repository;

import com.picturesAI.user_service.domain.Status;
import com.picturesAI.user_service.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByKeycloakId(UUID keycloakId);

    List<User> findByStatus(Status status, Pageable pageable);

    long countByStatus(Status status);

    @Modifying
    @Query("UPDATE User u SET u.updatedAt = :now WHERE u.id = :id AND u.status = :status")
    int touchForDeletion(@Param("id") UUID id,
                         @Param("status") Status status,
                         @Param("now") Instant now);
}