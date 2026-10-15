package com.picturesAI.tagging_service.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.picturesAI.tagging_service.config.CacheConfig;
import com.picturesAI.tagging_service.domain.TagVocabulary;
import com.picturesAI.tagging_service.repository.TagVocabularyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(VocabularyServiceTest.CacheTestConfig.class)
class VocabularyServiceTest {

    @Configuration
    @EnableCaching
    static class CacheTestConfig {

        @Bean
        CacheManager cacheManager() {
            CaffeineCacheManager manager =
                    new CaffeineCacheManager(CacheConfig.VOCABULARY_CACHE);
            manager.setCaffeine(Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofMinutes(5))
                    .maximumSize(1));
            return manager;
        }

        @Bean
        VocabularyService vocabularyService(TagVocabularyRepository repository) {
            return new VocabularyService(repository);
        }
    }

    @Autowired
    VocabularyService service;

    @Autowired
    CacheManager cacheManager;

    @MockitoBean
    TagVocabularyRepository repository;

    @Test
    void secondCallServedFromCacheRepositoryHitOnce() {
        cacheManager.getCache(CacheConfig.VOCABULARY_CACHE).clear();
        when(repository.findByActiveTrue()).thenReturn(List.of(cat(), dog()));

        List<TagVocabulary> first = service.activeVocabulary();
        List<TagVocabulary> second = service.activeVocabulary();

        assertThat(first).hasSize(2);
        assertThat(second).isSameAs(first);
        verify(repository, times(1)).findByActiveTrue();
    }

    @Test
    void onlyActiveVocabularyReturned() {
        cacheManager.getCache(CacheConfig.VOCABULARY_CACHE).clear();
        when(repository.findByActiveTrue()).thenReturn(List.of(cat(), dog()));

        List<TagVocabulary> result = service.activeVocabulary();

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(TagVocabulary::isActive);
        assertThat(result).extracting(TagVocabulary::getName).containsExactly("cat", "dog");
    }

    @Test
    void cacheEvictionTriggersRepositoryAgain() {
        cacheManager.getCache(CacheConfig.VOCABULARY_CACHE).clear();
        when(repository.findByActiveTrue()).thenReturn(List.of(cat()));

        service.activeVocabulary();
        cacheManager.getCache(CacheConfig.VOCABULARY_CACHE).clear();
        service.activeVocabulary();

        verify(repository, times(2)).findByActiveTrue();
    }

    private TagVocabulary cat() {
        return tag("cat", "subject", new String[]{"кот", "кошка", "kitten", "котёнок"});
    }

    private TagVocabulary dog() {
        return tag("dog", "subject", new String[]{"собака", "пёс", "puppy", "щенок"});
    }

    private TagVocabulary tag(String name, String category, String[] aliases) {
        TagVocabulary v = new TagVocabulary();
        v.setId(UUID.randomUUID());
        v.setName(name);
        v.setCategory(category);
        v.setAliases(aliases);
        v.setActive(true);
        return v;
    }
}