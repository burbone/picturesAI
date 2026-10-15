package com.picturesAI.generation_service.worker.retry;

public enum RetryAction {
    RETRY_RATE_LIMIT,
    RETRY_SERVER,
    DLQ
}