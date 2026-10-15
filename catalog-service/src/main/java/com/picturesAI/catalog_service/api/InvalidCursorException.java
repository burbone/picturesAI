package com.picturesAI.catalog_service.api;

public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String message) {
        super(message);
    }
}