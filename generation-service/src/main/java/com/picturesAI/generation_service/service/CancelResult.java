package com.picturesAI.generation_service.service;

import com.picturesAI.generation_service.domain.GenerationStatus;

public sealed interface CancelResult {

    record Cancelled() implements CancelResult {
    }

    record Accepted() implements CancelResult {
    }

    record NotFound() implements CancelResult {
    }

    record Forbidden() implements CancelResult {
    }

    record AlreadyTerminal(GenerationStatus status) implements CancelResult {
    }
}