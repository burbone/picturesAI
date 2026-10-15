package com.picturesAI.generation_service.domain;

public enum GenerationStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}
