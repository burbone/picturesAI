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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogWriteServiceTest {

    @Mock
    private CatalogImageRepository images;

    @Mock
    private CatalogImageTagRepository imageTags;

    @Mock
    private CatalogUserRepository users;

    @Mock
    private ProcessedEventRepository processed;

    private CatalogWriteService service;

    @BeforeEach
    void setUp() {
        service = new CatalogWriteService(images, imageTags, users, processed);
    }

    @Test
    void applyGenerationCompletedInsertsNewImage() {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        GenerationCompletedEvent event = new GenerationCompletedEvent(
                generationId, userId, "a cat",
                "orig", "k1", "thumb", "k2",
                "pollinations", Instant.parse("2025-01-01T00:00:00Z"));
        when(processed.existsById(anyString())).thenReturn(false);
        when(images.findById(generationId)).thenReturn(Optional.empty());

        service.applyGenerationCompleted(event);

        ArgumentCaptor<CatalogImage> captor = ArgumentCaptor.forClass(CatalogImage.class);
        verify(images).save(captor.capture());
        CatalogImage saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(generationId);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getPrompt()).isEqualTo("a cat");
        assertThat(saved.getOriginalsBucket()).isEqualTo("orig");
        assertThat(saved.getOriginalsKey()).isEqualTo("k1");
        assertThat(saved.getThumbnailsBucket()).isEqualTo("thumb");
        assertThat(saved.getThumbnailsKey()).isEqualTo("k2");
        assertThat(saved.getProvider()).isEqualTo("pollinations");
        assertThat(saved.getCompletedAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));

        ArgumentCaptor<ProcessedEvent> marker = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processed).save(marker.capture());
        assertThat(marker.getValue().getEventId()).isEqualTo("generation.completed:" + generationId);
        assertThat(marker.getValue().getEventType()).isEqualTo("generation.completed");
    }

    @Test
    void applyGenerationCompletedUpdatesExistingImage() {
        UUID generationId = UUID.randomUUID();
        CatalogImage existing = new CatalogImage();
        existing.setId(generationId);
        existing.setPrompt("old");
        when(processed.existsById(anyString())).thenReturn(false);
        when(images.findById(generationId)).thenReturn(Optional.of(existing));

        service.applyGenerationCompleted(new GenerationCompletedEvent(
                generationId, null, "new",
                "orig", "k1", "thumb", "k2",
                "pollinations", Instant.parse("2025-01-01T00:00:00Z")));

        ArgumentCaptor<CatalogImage> captor = ArgumentCaptor.forClass(CatalogImage.class);
        verify(images).save(captor.capture());
        assertThat(captor.getValue().getPrompt()).isEqualTo("new");
    }

    @Test
    void applyGenerationCompletedSkipsWhenAlreadyProcessed() {
        UUID generationId = UUID.randomUUID();
        when(processed.existsById("generation.completed:" + generationId)).thenReturn(true);

        service.applyGenerationCompleted(new GenerationCompletedEvent(
                generationId, null, "x",
                "orig", "k1", "thumb", "k2",
                "pollinations", Instant.now()));

        verify(images, never()).save(any());
        verify(processed, never()).save(any());
    }

    @Test
    void applyTagsExtractedDeletesOldThenSavesNew() {
        UUID generationId = UUID.randomUUID();
        TagsExtractedEvent event = new TagsExtractedEvent(
                generationId, null,
                List.of(new TagsExtractedEvent.Tag("cat", new BigDecimal("0.6")),
                        new TagsExtractedEvent.Tag("cute", new BigDecimal("0.4"))),
                Instant.parse("2025-01-01T00:00:00Z"));
        when(processed.existsById(anyString())).thenReturn(false);

        service.applyTagsExtracted(event);

        verify(imageTags).deleteByImageId(generationId);
        ArgumentCaptor<CatalogImageTag> captor = ArgumentCaptor.forClass(CatalogImageTag.class);
        verify(imageTags, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(t -> t.getId().getTag())
                .containsExactly("cat", "cute");
        assertThat(captor.getAllValues())
                .allMatch(t -> t.getId().getImageId().equals(generationId));

        ArgumentCaptor<ProcessedEvent> marker = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processed).save(marker.capture());
        assertThat(marker.getValue().getEventId()).isEqualTo("tags.extracted:" + generationId);
    }

    @Test
    void applyTagsExtractedSkipsBlankTagNames() {
        UUID generationId = UUID.randomUUID();
        TagsExtractedEvent event = new TagsExtractedEvent(
                generationId, null,
                List.of(new TagsExtractedEvent.Tag("cat", new BigDecimal("0.6")),
                        new TagsExtractedEvent.Tag("  ", new BigDecimal("0.4")),
                        new TagsExtractedEvent.Tag(null, new BigDecimal("0.1"))),
                Instant.now());
        when(processed.existsById(anyString())).thenReturn(false);

        service.applyTagsExtracted(event);

        ArgumentCaptor<CatalogImageTag> captor = ArgumentCaptor.forClass(CatalogImageTag.class);
        verify(imageTags, times(1)).save(captor.capture());
        assertThat(captor.getValue().getId().getTag()).isEqualTo("cat");
    }

    @Test
    void applyTagsExtractedWithEmptyListStillDeletesOld() {
        UUID generationId = UUID.randomUUID();
        TagsExtractedEvent event = new TagsExtractedEvent(
                generationId, null, List.of(), Instant.now());
        when(processed.existsById(anyString())).thenReturn(false);

        service.applyTagsExtracted(event);

        verify(imageTags).deleteByImageId(generationId);
        verify(imageTags, never()).save(any());
        verify(processed).save(any(ProcessedEvent.class));
    }

    @Test
    void applyTagsExtractedSkipsWhenAlreadyProcessed() {
        UUID generationId = UUID.randomUUID();
        when(processed.existsById("tags.extracted:" + generationId)).thenReturn(true);

        service.applyTagsExtracted(new TagsExtractedEvent(
                generationId, null, List.of(), Instant.now()));

        verify(imageTags, never()).deleteByImageId(any());
        verify(imageTags, never()).save(any());
        verify(processed, never()).save(any());
    }

    @Test
    void applyUserCreatedInsertsNewUser() {
        UUID userId = UUID.randomUUID();
        UUID keycloakId = UUID.randomUUID();
        UserCreatedEvent event = new UserCreatedEvent(
                userId, keycloakId, "alice", "Alice",
                "https://cdn/a.png", Instant.parse("2025-01-01T00:00:00Z"));
        when(processed.existsById(anyString())).thenReturn(false);
        when(users.findById(userId)).thenReturn(Optional.empty());

        service.applyUserCreated(event);

        ArgumentCaptor<CatalogUser> captor = ArgumentCaptor.forClass(CatalogUser.class);
        verify(users).save(captor.capture());
        CatalogUser saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(userId);
        assertThat(saved.getKeycloakId()).isEqualTo(keycloakId);
        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getDisplayName()).isEqualTo("Alice");
        assertThat(saved.getAvatarUrl()).isEqualTo("https://cdn/a.png");
        assertThat(saved.isAnonymized()).isFalse();

        verify(processed).save(any(ProcessedEvent.class));
    }

    @Test
    void applyUserCreatedSkipsWhenAlreadyProcessed() {
        UUID userId = UUID.randomUUID();
        when(processed.existsById("user.created:" + userId)).thenReturn(true);

        service.applyUserCreated(new UserCreatedEvent(
                userId, UUID.randomUUID(), "alice", "Alice", null, Instant.now()));

        verify(users, never()).save(any());
    }

    @Test
    void applyUserDeletedAnonymizesExistingUser() {
        UUID userId = UUID.randomUUID();
        CatalogUser existing = new CatalogUser();
        existing.setId(userId);
        existing.setKeycloakId(UUID.randomUUID());
        existing.setUsername("alice");
        existing.setDisplayName("Alice");
        existing.setAvatarUrl("https://cdn/a.png");
        when(processed.existsById(anyString())).thenReturn(false);
        when(users.findById(userId)).thenReturn(Optional.of(existing));

        service.applyUserDeleted(new UserDeletedEvent(
                userId, existing.getKeycloakId(), Instant.parse("2025-01-01T00:00:00Z")));

        ArgumentCaptor<CatalogUser> captor = ArgumentCaptor.forClass(CatalogUser.class);
        verify(users).save(captor.capture());
        CatalogUser saved = captor.getValue();
        assertThat(saved.isAnonymized()).isTrue();
        assertThat(saved.getKeycloakId()).isNull();
        assertThat(saved.getUsername()).isNull();
        assertThat(saved.getDisplayName()).isNull();
        assertThat(saved.getAvatarUrl()).isNull();

        verify(processed).save(any(ProcessedEvent.class));
    }

    @Test
    void applyUserDeletedMissingUserStillMarksProcessed() {
        UUID userId = UUID.randomUUID();
        when(processed.existsById(anyString())).thenReturn(false);
        when(users.findById(userId)).thenReturn(Optional.empty());

        service.applyUserDeleted(new UserDeletedEvent(userId, UUID.randomUUID(), Instant.now()));

        verify(users, never()).save(any());
        verify(processed).save(any(ProcessedEvent.class));
    }

    @Test
    void applyUserDeletedSkipsWhenAlreadyProcessed() {
        UUID userId = UUID.randomUUID();
        when(processed.existsById("user.deleted:" + userId)).thenReturn(true);

        service.applyUserDeleted(new UserDeletedEvent(userId, UUID.randomUUID(), Instant.now()));

        verify(users, never()).save(any());
        verify(processed, never()).save(any());
    }
}