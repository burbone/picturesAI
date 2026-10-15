package com.picturesAI.generation_service.web;

import com.picturesAI.generation_service.domain.Tier;
import com.picturesAI.generation_service.security.CurrentUserProvider;
import com.picturesAI.generation_service.service.CancelResult;
import com.picturesAI.generation_service.service.CancellationService;
import com.picturesAI.generation_service.service.CreateGenerationResult;
import com.picturesAI.generation_service.service.GenerationCreationService;
import com.picturesAI.generation_service.service.GenerationQueryService;
import com.picturesAI.generation_service.web.dto.CreateGenerationRequest;
import com.picturesAI.generation_service.web.dto.CreateGenerationResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/generations")
public class GenerationController {

    private final GenerationCreationService creationService;
    private final CancellationService cancellationService;
    private final GenerationQueryService queryService;
    private final CurrentUserProvider currentUser;

    public GenerationController(GenerationCreationService creationService,
                                CancellationService cancellationService,
                                GenerationQueryService queryService,
                                CurrentUserProvider currentUser) {
        this.creationService = creationService;
        this.cancellationService = cancellationService;
        this.queryService = queryService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateGenerationRequest request) {

        UUID userId = currentUser.userId();
        Tier tier = currentUser.roles().contains("PRO") ? Tier.PRO : Tier.FREE;

        CreateGenerationResult result = creationService.create(userId, idempotencyKey, request, tier);

        return switch (result) {
            case CreateGenerationResult.Created c -> {
                UUID jobId = c.jobIds().get(0);
                int count = c.jobIds().size();
                CreateGenerationResponse body = new CreateGenerationResponse(
                        jobId,
                        c.batchId(),
                        count,
                        "QUEUED",
                        c.tier().name(),
                        0,
                        count * 30,
                        "/api/v1/generations/" + jobId,
                        "/ws/generations/" + jobId);
                yield ResponseEntity.accepted().body(body);
            }
            case CreateGenerationResult.Duplicate d -> ResponseEntity
                    .status(409)
                    .body(Map.of("error", "duplicate", "jobId", d.jobId().toString()));
            case CreateGenerationResult.Conflict ignored -> ResponseEntity
                    .status(409)
                    .body(Map.of("error", "conflict"));
            case CreateGenerationResult.QuotaDenied ignored -> ResponseEntity
                    .status(402)
                    .body(Map.of("error", "quota_exceeded"));
        };
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable UUID id) {
        return queryService.findById(currentUser.userId(), id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "not_found")));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<?> getStatus(@PathVariable UUID id) {
        return queryService.findStatus(currentUser.userId(), id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "not_found")));
    }

    @GetMapping
    public ResponseEntity<?> history(@RequestParam(required = false) UUID cursor,
                                     @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(queryService.history(currentUser.userId(), cursor, limit));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancel(@PathVariable UUID id) {
        UUID userId = currentUser.userId();
        CancelResult result = cancellationService.cancel(userId, id);
        return switch (result) {
            case CancelResult.Cancelled ignored -> ResponseEntity
                    .accepted()
                    .body(Map.of("status", "CANCELLED"));
            case CancelResult.Accepted ignored -> ResponseEntity
                    .accepted()
                    .body(Map.of("status", "CANCELLING"));
            case CancelResult.NotFound ignored -> ResponseEntity
                    .status(404)
                    .body(Map.of("error", "not_found"));
            case CancelResult.Forbidden ignored -> ResponseEntity
                    .status(403)
                    .body(Map.of("error", "forbidden"));
            case CancelResult.AlreadyTerminal at -> ResponseEntity
                    .status(409)
                    .body(Map.of("error", "already_terminal", "status", at.status().name()));
        };
    }
}