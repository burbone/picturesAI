package com.picturesAI.generation_service.worker.cancel;

import java.util.UUID;

public interface CancellationRegistry {

    void mark(UUID generationId);

    boolean isMarked(UUID generationId);

    void unmark(UUID generationId);
}