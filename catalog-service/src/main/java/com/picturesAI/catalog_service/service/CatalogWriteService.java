package com.picturesAI.catalog_service.service;

import com.picturesAI.catalog_service.domain.CatalogImage;
import com.picturesAI.catalog_service.domain.CatalogImageTag;
import com.picturesAI.catalog_service.domain.CatalogUser;
import com.picturesAI.catalog_service.domain.ProcessedEvent;
import com.picturesAI.catalog_service.messaging.GenerationCompletedEvent;
import com.picturesAI.catalog_service.messaging.TagsExtractedEvent;
import com.picturesAI.catalog_service.messaging.UserCreatedEvent;
import com.picturesAI.catalog_service.messaging.UserDeletedEvent;
import com.picturesAI.catalog_service.repository.CatalogImageRepository;
import com.picturesAI.catalog_service.repository.CatalogImageTagRepository;
import com.picturesAI.catalog_service.repository.CatalogUserRepository;
import com.picturesAI.catalog_service.repository.ProcessedEventRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CatalogWriteService {

    static final String EVENT_GENERATION_COMPLETED = "generation.completed";
    static final String EVENT_TAGS_EXTRACTED = "tags.extracted";
    static final String EVENT_USER_CREATED = "user.created";
    static final String EVENT_USER_DELETED = "user.deleted";

    private final CatalogImageRepository images;
    private final CatalogImageTagRepository imageTags;
    private final CatalogUserRepository users;
    private final ProcessedEventRepository processed;

    public CatalogWriteService(CatalogImageRepository images,
                               CatalogImageTagRepository imageTags,
                               CatalogUserRepository users,
                               ProcessedEventRepository processed) {
        this.images = images;
        this.imageTags = imageTags;
        this.users = users;
        this.processed = processed;
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "feed", allEntries = true),
            @CacheEvict(value = "tags", allEntries = true)
    })
    public void applyGenerationCompleted(GenerationCompletedEvent event) {
        String eventId = EVENT_GENERATION_COMPLETED + ":" + event.generationId();
        if (processed.existsById(eventId)) {
            return;
        }
        CatalogImage image = images.findById(event.generationId()).orElseGet(CatalogImage::new);
        image.setId(event.generationId());
        image.setUserId(event.userId());
        image.setPrompt(event.prompt());
        image.setOriginalsBucket(event.originalsBucket());
        image.setOriginalsKey(event.originalsKey());
        image.setThumbnailsBucket(event.thumbnailsBucket());
        image.setThumbnailsKey(event.thumbnailsKey());
        image.setProvider(event.provider());
        image.setCompletedAt(event.completedAt());
        images.save(image);
        markProcessed(eventId, EVENT_GENERATION_COMPLETED);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "feed", allEntries = true),
            @CacheEvict(value = "tags", allEntries = true)
    })
    public void applyTagsExtracted(TagsExtractedEvent event) {
        String eventId = EVENT_TAGS_EXTRACTED + ":" + event.generationId();
        if (processed.existsById(eventId)) {
            return;
        }
        imageTags.deleteByImageId(event.generationId());
        if (event.tags() != null && !event.tags().isEmpty()) {
            List<CatalogImageTag> rows = event.tags().stream()
                    .filter(t -> t != null && t.name() != null && !t.name().isBlank())
                    .map(t -> CatalogImageTag.of(event.generationId(), t.name(), t.weight()))
                    .toList();
            for (CatalogImageTag row : rows) {
                imageTags.save(row);
            }
        }
        markProcessed(eventId, EVENT_TAGS_EXTRACTED);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "feed", allEntries = true),
            @CacheEvict(value = "tags", allEntries = true)
    })
    public void applyUserCreated(UserCreatedEvent event) {
        String eventId = EVENT_USER_CREATED + ":" + event.userId();
        if (processed.existsById(eventId)) {
            return;
        }
        CatalogUser user = users.findById(event.userId()).orElseGet(CatalogUser::new);
        user.setId(event.userId());
        user.setKeycloakId(event.keycloakId());
        user.setUsername(event.username());
        user.setDisplayName(event.displayName());
        user.setAvatarUrl(event.avatarUrl());
        users.save(user);
        markProcessed(eventId, EVENT_USER_CREATED);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "feed", allEntries = true),
            @CacheEvict(value = "tags", allEntries = true)
    })
    public void applyUserDeleted(UserDeletedEvent event) {
        String eventId = EVENT_USER_DELETED + ":" + event.userId();
        if (processed.existsById(eventId)) {
            return;
        }
        users.findById(event.userId()).ifPresent(user -> {
            user.setKeycloakId(null);
            user.setUsername(null);
            user.setDisplayName(null);
            user.setAvatarUrl(null);
            user.setAnonymized(true);
            users.save(user);
        });
        markProcessed(eventId, EVENT_USER_DELETED);
    }

    private void markProcessed(String eventId, String eventType) {
        ProcessedEvent marker = new ProcessedEvent();
        marker.setEventId(eventId);
        marker.setEventType(eventType);
        processed.save(marker);
    }
}