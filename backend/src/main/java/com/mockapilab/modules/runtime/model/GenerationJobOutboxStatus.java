package com.mockapilab.modules.runtime.model;

/**
 * Lifecycle status for durable transactional outbox events.
 */
public enum GenerationJobOutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    FAILED
}
