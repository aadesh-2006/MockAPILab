package com.mockapilab.modules.runtime.model;

/**
 * Status lifecycle enum for asynchronous generation jobs.
 */
public enum GenerationJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}
