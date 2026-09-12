package com.mockapilab.modules.ai.exception;

/**
 * Exception thrown when the AI provider is not configured or missing credentials.
 */
public class AiConfigurationException extends RuntimeException {
    public AiConfigurationException(String message) {
        super(message);
    }
}