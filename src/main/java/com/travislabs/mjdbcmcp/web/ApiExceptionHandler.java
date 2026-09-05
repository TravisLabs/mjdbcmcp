package com.travislabs.mjdbcmcp.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns the handful of expected failures into small JSON bodies the UI can display verbatim. */
@RestControllerAdvice(assignableTypes = AdminApi.class)
public class ApiExceptionHandler {

    /**
     * Handles 404 Not Found errors for missing resources.
     *
     * @param e the exception
     * @return 404 response entity
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * Handles 400 Bad Request errors for illegal arguments or states.
     *
     * @param e runtime exception
     * @return 400 response entity
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Handles 400 Bad Request errors for bean validation failures.
     *
     * @param e validation exception
     * @return 400 response entity with field error details
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Invalid request");
        return body(HttpStatus.BAD_REQUEST, detail);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status.value());
        payload.put("error", message == null ? status.getReasonPhrase() : message);
        return ResponseEntity.status(status).body(payload);
    }
}
