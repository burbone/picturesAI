package com.picturesAI.generation_service.service;

import com.picturesAI.generation_service.domain.Generation;
import com.picturesAI.generation_service.repository.GenerationRepository;
import com.picturesAI.generation_service.web.dto.GenerationPage;
import com.picturesAI.generation_service.web.dto.GenerationStatusView;
import com.picturesAI.generation_service.web.dto.GenerationView;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class GenerationQueryService {

    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 20;

    private final GenerationRepository repository;

    public GenerationQueryService(GenerationRepository repository) {
        this.repository = repository;
    }

    public Optional<GenerationView> findById(UUID userId, UUID id) {
        return repository.findById(id)
                .filter(g -> userId != null && userId.equals(g.getUserId()))
                .map(GenerationView::from);
    }

    public Optional<GenerationStatusView> findStatus(UUID userId, UUID id) {
        return repository.findById(id)
                .filter(g -> userId != null && userId.equals(g.getUserId()))
                .map(GenerationStatusView::from);
    }

    public GenerationPage history(UUID userId, UUID cursor, Integer limit) {
        if (userId == null) {
            return new GenerationPage(List.of(), null);
        }
        int size = clamp(limit);
        PageRequest page = PageRequest.of(0, size + 1);

        List<Generation> rows;
        if (cursor == null) {
            rows = repository.findByUserIdOrderByCreatedAtDesc(userId, page);
        } else {
            Instant cursorCreatedAt = repository.findById(cursor)
                    .map(Generation::getCreatedAt)
                    .orElse(Instant.now());
            rows = repository.findPageByUserIdBefore(userId, cursorCreatedAt, page);
        }

        boolean hasMore = rows.size() > size;
        List<Generation> slice = hasMore ? rows.subList(0, size) : rows;
        UUID nextCursor = hasMore && !slice.isEmpty()
                ? slice.get(slice.size() - 1).getId()
                : null;

        return new GenerationPage(slice.stream().map(GenerationView::from).toList(), nextCursor);
    }

    private int clamp(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}