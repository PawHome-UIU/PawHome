package com.pawhome.backend.config;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns validation failures and hand-written errors into clean JSON, instead of
 * leaking Spring's default error body or a stack trace.
 *
 * Raise every error in this codebase as a ResponseStatusException with a
 * sentence a real user can read:
 *
 *     throw new ResponseStatusException(HttpStatus.CONFLICT,
 *             "That username is already taken. Please choose another.");
 *
 * Without the handler below those sentences are stripped out (application.yaml
 * sets include-message: on_param) and the frontend shows a bare "Bad Request".
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidBody(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Validation failed",
                "fields", fields));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException ex) {
        Map<String, String> fields = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        v -> v.getMessage(),
                        (a, b) -> a));
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Validation failed",
                "fields", fields));
    }

    // Our own errors. The original status is preserved -- 404 stays 404,
    // 409 stays 409 -- and the body gains the sentence written at the throw site.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        String reason = ex.getReason();
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", (reason == null || reason.isBlank())
                        ? "The request could not be completed."
                        : reason));
    }
}
