-- =========================================================================
-- V6: Schema for Contract Drift Detection (Milestone 10)
-- =========================================================================

CREATE TABLE contract_drift_reports (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    from_version_id UUID NOT NULL,
    to_version_id UUID NOT NULL,
    from_version_number INT NOT NULL,
    to_version_number INT NOT NULL,
    breaking_change_count INT NOT NULL DEFAULT 0,
    non_breaking_change_count INT NOT NULL DEFAULT 0,
    informational_change_count INT NOT NULL DEFAULT 0,
    overall_severity VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_drift_reports_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT fk_drift_reports_contract FOREIGN KEY (contract_id) REFERENCES contracts(id) ON DELETE CASCADE,
    CONSTRAINT fk_drift_reports_from_version FOREIGN KEY (from_version_id) REFERENCES contract_versions(id) ON DELETE CASCADE,
    CONSTRAINT fk_drift_reports_to_version FOREIGN KEY (to_version_id) REFERENCES contract_versions(id) ON DELETE CASCADE
);

CREATE TABLE contract_drift_changes (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL,
    change_type VARCHAR(64) NOT NULL,
    classification VARCHAR(32) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    path VARCHAR(255),
    method VARCHAR(16),
    location VARCHAR(255),
    old_value TEXT,
    new_value TEXT,
    message VARCHAR(500) NOT NULL,
    CONSTRAINT fk_drift_changes_report FOREIGN KEY (report_id) REFERENCES contract_drift_reports(id) ON DELETE CASCADE
);

CREATE INDEX idx_drift_reports_contract_id ON contract_drift_reports(contract_id);
CREATE INDEX idx_drift_reports_project_id ON contract_drift_reports(project_id);
CREATE INDEX idx_drift_changes_report_id ON contract_drift_changes(report_id);
