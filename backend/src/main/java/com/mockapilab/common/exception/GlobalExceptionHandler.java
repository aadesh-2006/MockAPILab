package com.mockapilab.common.exception;

import com.mockapilab.common.api.ApiResponse;
import com.mockapilab.common.logging.CorrelationIdFilter;
import com.mockapilab.modules.ai.exception.AiConfigurationException;
import com.mockapilab.modules.ai.exception.AiProviderException;
import com.mockapilab.modules.contract.validation.ContractValidationException;
import com.mockapilab.modules.runtime.messaging.GenerationJobException;
import com.mockapilab.modules.runtime.state.RuntimeStateException;
import jakarta.servlet.http.HttpServletRequest;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized exception handler providing consistent, sanitized structured API error responses.
 * Attaches request correlation IDs and prevents sensitive internal implementation leaks.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleValidationExceptions(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        Map<String, Object> errorDetails = new LinkedHashMap<>();
        errorDetails.put("timestamp", Instant.now());
        errorDetails.put("status", HttpStatus.BAD_REQUEST.value());
        errorDetails.put("error", "Validation Failed");
        errorDetails.put("message", "Validation failed for one or more fields");
        errorDetails.put("path", request.getRequestURI());
        errorDetails.put("requestId", CorrelationIdFilter.getCorrelationId());
        errorDetails.put("details", fieldErrors);

        // Retain field error mappings directly at root of data payload for backward compatibility
        errorDetails.putAll(fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Validation failed", errorDetails));
    }

    @ExceptionHandler(ContractValidationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleContractValidationException(
            ContractValidationException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Contract Validation Error", ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleMalformedJson(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Malformed JSON", "Malformed request payload: Invalid JSON format", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleIllegalState(
            IllegalStateException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request);
    }

    @ExceptionHandler({InvalidCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleAuthenticationException(
            Exception ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid credentials or unauthorized access", request);
    }

    @ExceptionHandler({ForbiddenException.class, AccessDeniedException.class})
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleForbiddenException(
            Exception ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage() != null ? ex.getMessage() : "Access denied", request);
    }

    @ExceptionHandler({ResourceNotFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleNotFoundException(
            Exception ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage() != null ? ex.getMessage() : "Resource not found", request);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleDuplicateResource(
            DuplicateResourceException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request);
    }

    @ExceptionHandler(RuntimeStateException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleRuntimeStateException(
            RuntimeStateException ex,
            HttpServletRequest request
    ) {
        log.error("Runtime state error encountered: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "Runtime state service error: " + ex.getMessage(), request);
    }

    @ExceptionHandler(GenerationJobException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleGenerationJobException(
            GenerationJobException ex,
            HttpServletRequest request
    ) {
        log.error("Generation job error encountered: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "Generation job service error: " + ex.getMessage(), request);
    }

    @ExceptionHandler(AiConfigurationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleAiConfigurationException(
            AiConfigurationException ex,
            HttpServletRequest request
    ) {
        log.warn("AI configuration issue: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "AI service is not properly configured: " + ex.getMessage(), request);
    }

    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleAiProviderException(
            AiProviderException ex,
            HttpServletRequest request
    ) {
        log.error("AI provider error encountered: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "AI extraction provider error: " + ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleGeneralException(
            Exception ex,
            HttpServletRequest request
    ) {
        log.error("Unhandled exception encountered [requestId={}]: ", CorrelationIdFilter.getCorrelationId(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred", request);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> buildErrorResponse(
            HttpStatus status,
            String errorType,
            String message,
            HttpServletRequest request
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", status.value());
        body.put("error", errorType);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("requestId", CorrelationIdFilter.getCorrelationId());

        return ResponseEntity.status(status).body(ApiResponse.error(message, body));
    }
}