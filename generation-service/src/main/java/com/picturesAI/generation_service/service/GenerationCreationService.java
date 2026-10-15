package com.picturesAI.generation_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.domain.Generation;
import com.picturesAI.generation_service.domain.GenerationStatus;
import com.picturesAI.generation_service.domain.OutboxEvent;
import com.picturesAI.generation_service.domain.Tier;
import com.picturesAI.generation_service.idempotency.IdempotencyResult;
import com.picturesAI.generation_service.idempotency.IdempotencyService;
import com.picturesAI.generation_service.quota.QuotaClient;
import com.picturesAI.generation_service.repository.GenerationRepository;
import com.picturesAI.generation_service.repository.OutboxRepository;
import com.picturesAI.generation_service.web.dto.CreateGenerationRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class GenerationCreationService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final GenerationRepository generations;
    private final OutboxRepository outbox;
    private final IdempotencyService idempotency;
    private final QuotaClient quotaClient;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public GenerationCreationService(
            GenerationRepository generations,
            OutboxRepository outbox,
            IdempotencyService idempotency,
            QuotaClient quotaClient,
            ObjectMapper mapper,
            TransactionTemplate tx) {
        this.generations = generations;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.quotaClient = quotaClient;
        this.mapper = mapper;
        this.tx = tx;
    }

    public CreateGenerationResult create(UUID userId, String idempotencyKey,
                                         CreateGenerationRequest request, Tier tier) {
        Optional<Generation> existing =
                generations.findFirstByUserIdAndIdempotencyKeyOrderByCreatedAtAsc(userId, idempotencyKey);
        if (existing.isPresent()) {
            return new CreateGenerationResult.Duplicate(existing.get().getId());
        }

        IdempotencyResult acquired = idempotency.acquire(userId, idempotencyKey, IDEMPOTENCY_TTL);
        if (acquired instanceof IdempotencyResult.Duplicate d) {
            return new CreateGenerationResult.Duplicate(d.generationId());
        }
        if (acquired instanceof IdempotencyResult.Conflict) {
            return new CreateGenerationResult.Conflict();
        }

        int count = request.countOrDefault();
        UUID batchId = count > 1 ? UUID.randomUUID() : null;
        List<UUID> jobIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            jobIds.add(UUID.randomUUID());
        }

        List<UUID> reserved = new ArrayList<>(count);
        for (UUID id : jobIds) {
            if (!quotaClient.reserve(userId, id)) {
                reserved.forEach(quotaClient::release);
                idempotency.release(userId, idempotencyKey);
                return new CreateGenerationResult.QuotaDenied();
            }
            reserved.add(id);
        }

        try {
            tx.executeWithoutResult(status -> {
                for (UUID id : jobIds) {
                    Generation g = buildGeneration(id, userId, request, tier, batchId, idempotencyKey);
                    generations.save(g);
                    outbox.save(buildOutbox(g));
                }
            });
        } catch (RuntimeException e) {
            reserved.forEach(quotaClient::release);
            idempotency.release(userId, idempotencyKey);
            throw e;
        }

        idempotency.bind(userId, idempotencyKey, jobIds.get(0), IDEMPOTENCY_TTL);

        return new CreateGenerationResult.Created(jobIds, batchId, tier);
    }

    private Generation buildGeneration(UUID id, UUID userId, CreateGenerationRequest request,
                                       Tier tier, UUID batchId, String idempotencyKey) {
        Generation g = new Generation();
        g.setId(id);
        g.setUserId(userId);
        g.setPrompt(request.prompt());
        g.setNegativePrompt(request.negativePrompt());
        g.setModel(request.model());
        g.setSize(request.size());
        g.setTier(tier);
        g.setStatus(GenerationStatus.QUEUED);
        g.setPublicFlag(request.publicOrDefault());
        g.setBatchId(batchId);
        g.setIdempotencyKey(idempotencyKey);
        return g;
    }

    private OutboxEvent buildOutbox(Generation g) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generationId", g.getId().toString());
        payload.put("userId", g.getUserId().toString());
        payload.put("tier", g.getTier().name());
        payload.put("model", g.getModel());
        payload.put("size", g.getSize());
        payload.put("prompt", g.getPrompt());
        payload.put("createdAt", Instant.now().toString());

        OutboxEvent e = new OutboxEvent();
        e.setAggregateId(g.getId());
        e.setEventType("generation.requested." + g.getTier().name().toLowerCase());
        try {
            e.setPayload(mapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox payload", ex);
        }
        return e;
    }
}