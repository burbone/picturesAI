package com.picturesAI.generation_service.repository;

import com.picturesAI.generation_service.domain.Generation;
import com.picturesAI.generation_service.domain.GenerationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GenerationRepository extends JpaRepository<Generation, UUID> {

    Optional<Generation> findFirstByUserIdAndIdempotencyKeyOrderByCreatedAtAsc(UUID userId, String idempotencyKey);

    Optional<Generation> findByUserIdAndIdempotencyKeyAndCreatedAt(UUID userId, String idempotencyKey, Instant createdAt);

    List<Generation> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @Query("SELECT g FROM Generation g WHERE g.userId = :userId AND g.createdAt < :cursor "
            + "ORDER BY g.createdAt DESC")
    List<Generation> findPageByUserIdBefore(@Param("userId") UUID userId,
                                            @Param("cursor") Instant cursor,
                                            Pageable pageable);

    List<Generation> findByBatchId(UUID batchId);

    long countByStatus(GenerationStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Generation g SET g.status = :to, "
            + "g.startedAt = COALESCE(g.startedAt, :at) "
            + "WHERE g.id = :id AND g.status = :from")
    int transitionToProcessing(@Param("id") UUID id,
                               @Param("from") GenerationStatus from,
                               @Param("to") GenerationStatus to,
                               @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Generation g SET g.status = :to, g.imageUrl = :imageUrl, "
            + "g.thumbnailUrl = :thumbnailUrl, g.provider = :provider, "
            + "g.providerJobId = :providerJobId, g.completedAt = :at "
            + "WHERE g.id = :id AND g.status = :from")
    int transitionToCompleted(@Param("id") UUID id,
                              @Param("from") GenerationStatus from,
                              @Param("to") GenerationStatus to,
                              @Param("imageUrl") String imageUrl,
                              @Param("thumbnailUrl") String thumbnailUrl,
                              @Param("provider") String provider,
                              @Param("providerJobId") String providerJobId,
                              @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Generation g SET g.status = :to, g.errorMessage = :errorMessage, "
            + "g.completedAt = :at, g.retryCount = g.retryCount + 1 "
            + "WHERE g.id = :id AND g.status = :from")
    int transitionToFailed(@Param("id") UUID id,
                           @Param("from") GenerationStatus from,
                           @Param("to") GenerationStatus to,
                           @Param("errorMessage") String errorMessage,
                           @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Generation g SET g.status = :to, "
            + "g.lastErrorMessage = :errorMessage, g.retryCount = g.retryCount + 1 "
            + "WHERE g.id = :id AND g.status = :from")
    int transitionToRetry(@Param("id") UUID id,
                          @Param("from") GenerationStatus from,
                          @Param("to") GenerationStatus to,
                          @Param("errorMessage") String errorMessage);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Generation g SET g.status = :to, g.cancelledAt = :at "
            + "WHERE g.id = :id AND g.status IN :fromStatuses")
    int transitionToCancelled(@Param("id") UUID id,
                              @Param("fromStatuses") Collection<GenerationStatus> fromStatuses,
                              @Param("to") GenerationStatus to,
                              @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Generation g SET g.userId = NULL, g.prompt = '[deleted]' "
            + "WHERE g.userId = :userId")
    int anonymizeByUserId(@Param("userId") UUID userId);
}