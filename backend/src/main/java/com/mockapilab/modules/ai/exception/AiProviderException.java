package com.mockapilab.modules.ai.exception;

/**
 * Exception thrown when an AI provider communication, quota, timeout, or parsing error occurs.
 */
public class AiProviderException extends RuntimeException {
    public AiProviderException(String message) {
        super(message);
    }

    public AiProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}