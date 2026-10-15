package com.picturesAI.tagging_service.repository;

import com.picturesAI.tagging_service.domain.GenerationTag;
import com.picturesAI.tagging_service.domain.GenerationTagId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface GenerationTagRepository extends JpaRepository<GenerationTag, GenerationTagId> {

    @Query("SELECT COUNT(t) > 0 FROM GenerationTag t WHERE t.id.generationId = :generationId")
    boolean existsByGenerationId(@Param("generationId") UUID generationId);

    @Query("SELECT t FROM GenerationTag t WHERE t.id.generationId = :generationId")
    List<GenerationTag> findByGenerationId(@Param("generationId") UUID generationId);
}