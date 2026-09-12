-- =========================================================================
-- V4: Schema for Asynchronous Generation Jobs (Milestone 7)
-- =========================================================================

CREATE TABLE generation_jobs (
    id UUID PRIMARY KEY,
    runtime_id UUID NOT NULL,
    project_id UUID NOT NULL,
    collection_path VARCHAR(255) NOT NULL,
    entity_count INT NOT NULL,
    requested_seed BIGINT,
    effective_seed BIGINT,
    status VARCHAR(32) NOT NULL,
    error_message VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_generation_jobs_runtime FOREIGN KEY (runtime_id) REFERENCES mock_runtimes(id) ON DELETE CASCADE,
    CONSTRAINT fk_generation_jobs_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

CREATE INDEX idx_generation_jobs_runtime_id ON generation_jobs(runtime_id);
CREATE INDEX idx_generation_jobs_project_id ON generation_jobs(project_id);
CREATE INDEX idx_generation_jobs_status ON generation_jobs(status);
CREATE INDEX idx_generation_jobs_created_at ON generation_jobs(created_at);
