package com.picturesAI.tagging_service.repository;

import com.picturesAI.tagging_service.domain.TagVocabulary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TagVocabularyRepository extends JpaRepository<TagVocabulary, UUID> {

    List<TagVocabulary> findByActiveTrue();

    Optional<TagVocabulary> findByName(String name);
}