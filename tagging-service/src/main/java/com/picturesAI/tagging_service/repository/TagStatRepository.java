package com.picturesAI.tagging_service.repository;

import com.picturesAI.tagging_service.domain.TagStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.UUID;

public interface TagStatRepository extends JpaRepository<TagStat, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TagStat s SET s.usageCount = s.usageCount + 1 WHERE s.tagId IN :tagIds")
    int incrementUsage(@Param("tagIds") Collection<UUID> tagIds);
}