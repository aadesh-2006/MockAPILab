-- =========================================================================
-- V7: Durable Transactional Outbox for Generation Job Kafka Publication
-- =========================================================================

CREATE TABLE generation_job_outbox (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL,
    topic VARCHAR(255) NOT NULL,
    event_key VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(128),
    locked_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_generation_job_outbox_job FOREIGN KEY (job_id) REFERENCES generation_jobs(id) ON DELETE CASCADE,
    CONSTRAINT uq_generation_job_outbox_job_id UNIQUE (job_id)
);

CREATE INDEX idx_generation_job_outbox_poll ON generation_job_outbox(status, next_attempt_at, created_at);
CREATE INDEX idx_generation_job_outbox_locked_at ON generation_job_outbox(status, locked_at);
