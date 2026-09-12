package com.mockapilab.modules.runtime.messaging;

/**
 * Exception thrown when generation job submission, queuing, or event publishing fails.
 */
public class GenerationJobException extends RuntimeException {
    public GenerationJobException(String message) {
        super(message);
    }

    public GenerationJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
