-- =========================================================================
-- V2: Schema for Contracts and Contract Versions (Milestone 3)
-- =========================================================================

CREATE TABLE contracts (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_contracts_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

CREATE INDEX idx_contracts_project_id ON contracts(project_id);

CREATE TABLE contract_versions (
    id UUID PRIMARY KEY,
    contract_id UUID NOT NULL,
    version_number INT NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    normalized_definition JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_contract_versions_contract FOREIGN KEY (contract_id) REFERENCES contracts(id) ON DELETE CASCADE,
    CONSTRAINT uq_contract_version UNIQUE (contract_id, version_number)
);

CREATE INDEX idx_contract_versions_contract_id ON contract_versions(contract_id);
