package com.picturesAI.generation_service.storage;

import java.util.UUID;

public interface ImageStorage {

    StoredImage store(UUID userId, UUID generationId, byte[] data, String contentType);

    record StoredImage(
            String imageUrl,
            String thumbnailUrl,
            String originalsBucket,
            String originalsKey,
            String thumbnailsBucket,
            String thumbnailsKey) {
    }
}