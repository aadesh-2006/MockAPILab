-- =========================================================================
-- V3: Schema for Mock Runtimes (Milestone 4)
-- =========================================================================

CREATE TABLE mock_runtimes (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    contract_version_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    stopped_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_mock_runtimes_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_mock_runtimes_contract_version FOREIGN KEY (contract_version_id) REFERENCES contract_versions(id) ON DELETE CASCADE
);

CREATE INDEX idx_mock_runtimes_project_id ON mock_runtimes(project_id);
CREATE INDEX idx_mock_runtimes_contract_version_id ON mock_runtimes(contract_version_id);
CREATE INDEX idx_mock_runtimes_status ON mock_runtimes(status);