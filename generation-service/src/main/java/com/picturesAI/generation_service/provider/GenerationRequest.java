package com.picturesAI.generation_service.provider;

public record GenerationRequest(
        String prompt,
        String negativePrompt,
        String model,
        String size) {
}