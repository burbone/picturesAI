package com.picturesAI.generation_service.worker.retry;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RetryQueueRepository extends JpaRepository<RetryQueueEntry, UUID> {

    List<RetryQueueEntry> findByProcessedAtIsNullAndScheduledAtBeforeOrderByScheduledAtAsc(
            Instant now, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RetryQueueEntry r SET r.processedAt = :at "
            + "WHERE r.id = :id AND r.processedAt IS NULL")
    int markProcessed(@Param("id") UUID id, @Param("at") Instant at);

    long countByProcessedAtIsNull();
}