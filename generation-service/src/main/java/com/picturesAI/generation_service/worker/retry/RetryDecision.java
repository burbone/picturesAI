package com.picturesAI.generation_service.worker.retry;

public enum RetryDecision {
    SCHEDULED,
    EXHAUSTED,
    DLQ
}