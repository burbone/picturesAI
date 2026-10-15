package com.picturesAI.generation_service.provider;

public record ProviderResponse(
        byte[] data,
        String contentType,
        String providerJobId,
        String providerName) {
}