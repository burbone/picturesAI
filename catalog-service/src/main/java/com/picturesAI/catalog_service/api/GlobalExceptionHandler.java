package com.picturesAI.catalog_service.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCursorException.class)
    public ResponseEntity<Map<String, String>> invalidCursor(InvalidCursorException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "invalid_cursor", "message", e.getMessage()));
    }

    @ExceptionHandler(ImageNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ImageNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "not_found", "message", e.getMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> constraint(ConstraintViolationException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "invalid_request", "message", e.getMessage()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "invalid_request", "message", e.getMessage()));
    }
}