package com.picturesAI.generation_service.service;

import com.picturesAI.generation_service.domain.Generation;
import com.picturesAI.generation_service.domain.GenerationStatus;
import com.picturesAI.generation_service.domain.Tier;
import com.picturesAI.generation_service.repository.GenerationRepository;
import com.picturesAI.generation_service.web.dto.GenerationPage;
import com.picturesAI.generation_service.web.dto.GenerationStatusView;
import com.picturesAI.generation_service.web.dto.GenerationView;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationQueryServiceTest {

    private final GenerationRepository repository = mock(GenerationRepository.class);
    private final GenerationQueryService service = new GenerationQueryService(repository);

    @Test
    void findByIdReturnsViewForOwner() {
        UUID userId = UUID.randomUUID();
        Generation g = generation(userId);
        when(repository.findById(g.getId())).thenReturn(Optional.of(g));

        Optional<GenerationView> view = service.findById(userId, g.getId());

        assertThat(view).isPresent();
        assertThat(view.get().id()).isEqualTo(g.getId());
        assertThat(view.get().status()).isEqualTo("QUEUED");
    }

    @Test
    void findByIdReturnsEmptyForOtherUser() {
        Generation g = generation(UUID.randomUUID());
        when(repository.findById(g.getId())).thenReturn(Optional.of(g));

        assertThat(service.findById(UUID.randomUUID(), g.getId())).isEmpty();
    }

    @Test
    void findByIdReturnsEmptyForNullUser() {
        Generation g = generation(UUID.randomUUID());
        when(repository.findById(g.getId())).thenReturn(Optional.of(g));

        assertThat(service.findById(null, g.getId())).isEmpty();
    }

    @Test
    void findStatusMapsStatusView() {
        UUID userId = UUID.randomUUID();
        Generation g = generation(userId);
        g.setStatus(GenerationStatus.COMPLETED);
        g.setImageUrl("http://img");
        g.setThumbnailUrl("http://thumb");
        when(repository.findById(g.getId())).thenReturn(Optional.of(g));

        Optional<GenerationStatusView> view = service.findStatus(userId, g.getId());

        assertThat(view).isPresent();
        assertThat(view.get().status()).isEqualTo("COMPLETED");
        assertThat(view.get().imageUrl()).isEqualTo("http://img");
    }

    @Test
    void historyReturnsFirstPageWhenNoCursor() {
        UUID userId = UUID.randomUUID();
        Generation g1 = generation(userId);
        Generation g2 = generation(userId);
        when(repository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(Pageable.class)))
                .thenReturn(List.of(g1, g2));

        GenerationPage page = service.history(userId, null, 10);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void historyReturnsNextCursorWhenMoreResults() {
        UUID userId = UUID.randomUUID();
        Generation g1 = generation(userId);
        Generation g2 = generation(userId);
        Generation g3 = generation(userId);
        when(repository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(Pageable.class)))
                .thenReturn(List.of(g1, g2, g3));

        GenerationPage page = service.history(userId, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isEqualTo(g2.getId());
    }

    @Test
    void historyClampsLimit() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(Pageable.class)))
                .thenReturn(List.of());

        service.history(userId, null, 10_000);
        service.history(userId, null, -5);
        service.history(userId, null, null);
    }

    @Test
    void historyReturnsEmptyForNullUser() {
        GenerationPage page = service.history(null, null, 10);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void historyUsesCursorBranchWhenCursorGiven() {
        UUID userId = UUID.randomUUID();
        Generation cursorGen = generation(userId);
        when(repository.findById(cursorGen.getId())).thenReturn(Optional.of(cursorGen));
        when(repository.findPageByUserIdBefore(eq(userId), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        GenerationPage page = service.history(userId, cursorGen.getId(), 10);

        assertThat(page.items()).isEmpty();
    }

    private Generation generation(UUID userId) {
        Generation g = new Generation();
        g.setId(UUID.randomUUID());
        g.setUserId(userId);
        g.setPrompt("p");
        g.setModel("m");
        g.setSize("512x512");
        g.setTier(Tier.FREE);
        g.setStatus(GenerationStatus.QUEUED);
        g.setCreatedAt(Instant.now());
        return g;
    }
}