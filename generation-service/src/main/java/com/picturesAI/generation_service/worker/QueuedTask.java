package com.picturesAI.generation_service.worker;

public record QueuedTask(GenerationTask task, Runnable onComplete) {
}