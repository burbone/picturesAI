package com.picturesAI.generation_service.web.dto;

import java.util.UUID;

public record CreateGenerationResponse(
        UUID jobId,
        UUID batchId,
        int count,
        String status,
        String tier,
        int queuePosition,
        int estimatedWaitSeconds,
        String statusUrl,
        String streamUrl) {
}