package com.picturesAI.generation_service.quota;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile({"dev", "demo-local"})
public class AlwaysAllowQuotaClient implements QuotaClient {

    @Override
    public boolean reserve(UUID userId, UUID generationId) {
        return true;
    }

    @Override
    public void confirm(UUID generationId) {
    }

    @Override
    public void release(UUID generationId) {
    }
}