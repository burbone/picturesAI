package com.picturesAI.generation_service.provider;

public interface ImageProvider {

    ProviderResponse generate(GenerationRequest request);

    String name();

    boolean supports(String model);
}