package com.picturesAI.catalog_service.api;

import java.util.UUID;

public class ImageNotFoundException extends RuntimeException {

    public ImageNotFoundException(UUID id) {
        super("Image not found: " + id);
    }
}