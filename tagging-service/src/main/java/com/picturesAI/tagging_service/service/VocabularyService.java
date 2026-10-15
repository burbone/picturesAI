package com.picturesAI.tagging_service.service;

import com.picturesAI.tagging_service.config.CacheConfig;
import com.picturesAI.tagging_service.domain.TagVocabulary;
import com.picturesAI.tagging_service.repository.TagVocabularyRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class VocabularyService {

    private final TagVocabularyRepository repository;

    public VocabularyService(TagVocabularyRepository repository) {
        this.repository = repository;
    }

    @Cacheable(cacheNames = CacheConfig.VOCABULARY_CACHE, key = "'active'")
    @Transactional(readOnly = true)
    public List<TagVocabulary> activeVocabulary() {
        return repository.findByActiveTrue();
    }
}