-- =========================================================================
-- V5: Schema for Interactive Scenario Engine & Failure Injector (Milestone 9)
-- =========================================================================

CREATE TABLE scenarios (
    id UUID PRIMARY KEY,
    runtime_id UUID NOT NULL,
    project_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    path_pattern VARCHAR(255) NOT NULL,
    http_method VARCHAR(16),
    action VARCHAR(32) NOT NULL,
    status_code INT,
    delay_ms INT,
    probability_percent INT,
    max_executions INT,
    execution_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_scenarios_runtime FOREIGN KEY (runtime_id) REFERENCES mock_runtimes(id) ON DELETE CASCADE,
    CONSTRAINT fk_scenarios_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT chk_scenarios_status_code CHECK (status_code IS NULL OR (status_code >= 100 AND status_code <= 599)),
    CONSTRAINT chk_scenarios_delay_ms CHECK (delay_ms IS NULL OR (delay_ms >= 0 AND delay_ms <= 30000)),
    CONSTRAINT chk_scenarios_probability CHECK (probability_percent IS NULL OR (probability_percent >= 0 AND probability_percent <= 100)),
    CONSTRAINT chk_scenarios_max_executions CHECK (max_executions IS NULL OR max_executions > 0)
);

CREATE INDEX idx_scenarios_runtime_id ON scenarios(runtime_id);
CREATE INDEX idx_scenarios_runtime_status ON scenarios(runtime_id, status);
CREATE INDEX idx_scenarios_runtime_path ON scenarios(runtime_id, path_pattern);
