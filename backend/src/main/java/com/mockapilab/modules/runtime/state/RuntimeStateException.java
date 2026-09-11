package com.mockapilab.modules.runtime.state;

/**
 * Unchecked exception thrown when runtime state operations fail (e.g., Redis connectivity,
 * serialization errors, or state mutation failures).
 */
public class RuntimeStateException extends RuntimeException {

    public RuntimeStateException(String message) {
        super(message);
    }

    public RuntimeStateException(String message, Throwable cause) {
        super(message, cause);
    }
}