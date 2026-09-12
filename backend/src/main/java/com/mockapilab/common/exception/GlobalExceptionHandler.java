package com.mockapilab.common.exception;

import com.mockapilab.common.api.ApiResponse;
import com.mockapilab.modules.ai.exception.AiConfigurationException;
import com.mockapilab.modules.ai.exception.AiProviderException;
import com.mockapilab.modules.contract.validation.ContractValidationException;
import com.mockapilab.modules.runtime.messaging.GenerationJobException;
import com.mockapilab.modules.runtime.state.RuntimeStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralized exception handler providing consistent structured API error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Validation failed", errors));
    }

    @ExceptionHandler(ContractValidationException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleContractValidationException(ContractValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage(), Map.of("error", ex.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleMalformedJson(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Malformed request payload", Map.of("error", "Invalid JSON format")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage(), Map.of("error", ex.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), Map.of("error", ex.getMessage())));
    }

    @ExceptionHandler({InvalidCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<ApiResponse<Map<String, String>>> handleAuthenticationException(Exception ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Invalid credentials or unauthorized access", Map.of("error", "Authentication failed")));
    }

    @ExceptionHandler({ForbiddenException.class, AccessDeniedException.class})
    public ResponseEntity<ApiResponse<Map<String, String>>> handleForbiddenException(Exception ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ex.getMessage(), Map.of("error", "Access denied")));
    }

    @ExceptionHandler({ResourceNotFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Map<String, String>>> handleNotFoundException(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage(), Map.of("error", ex.getMessage())));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleDuplicateResource(DuplicateResourceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), Map.of("error", ex.getMessage())));
    }

    @ExceptionHandler(RuntimeStateException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleRuntimeStateException(RuntimeStateException ex) {
        log.error("Runtime state error encountered: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("Runtime state service error: " + ex.getMessage(), Map.of("error", ex.getMessage())));
    }

    @ExceptionHandler(GenerationJobException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleGenerationJobException(GenerationJobException ex) {
        log.error("Generation job error encountered: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("Generation job service error: " + ex.getMessage(), Map.of("error", ex.getMessage())));
    }

    @ExceptionHandler(AiConfigurationException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleAiConfigurationException(AiConfigurationException ex) {
        log.warn("AI configuration issue: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("AI service is not properly configured: " + ex.getMessage(), Map.of("error", ex.getMessage())));
    }

    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleAiProviderException(AiProviderException ex) {
        log.error("AI provider error encountered: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("AI extraction provider error: " + ex.getMessage(), Map.of("error", ex.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleGeneralException(Exception ex) {
        log.error("Unhandled exception encountered: ", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred", Map.of("error", "Internal server error")));
    }
}
