package com.picturesAI.generation_service.quota;

import java.util.UUID;

public interface QuotaClient {

    boolean reserve(UUID userId, UUID generationId);

    void confirm(UUID generationId);

    void release(UUID generationId);
}