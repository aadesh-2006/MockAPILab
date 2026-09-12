package com.mockapilab.modules.scenario.model;

import com.mockapilab.modules.project.model.Project;
import com.mockapilab.modules.runtime.model.MockRuntime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Scenario entity defining a dynamic behavior rule or failure injection for a mock runtime.
 */
@Entity
@Table(name = "scenarios")
public class Scenario {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "runtime_id", nullable = false)
    private MockRuntime runtime;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ScenarioStatus status = ScenarioStatus.ACTIVE;

    @Column(name = "path_pattern", nullable = false, length = 255)
    private String pathPattern;

    @Column(name = "http_method", length = 16)
    private String httpMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private ScenarioAction action;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "delay_ms")
    private Integer delayMs;

    @Column(name = "probability_percent")
    private Integer probabilityPercent;

    @Column(name = "max_executions")
    private Integer maxExecutions;

    @Column(name = "execution_count", nullable = false)
    private int executionCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Scenario() {
    }

    public Scenario(
            MockRuntime runtime,
            Project project,
            String name,
            String description,
            ScenarioStatus status,
            String pathPattern,
            String httpMethod,
            ScenarioAction action,
            Integer statusCode,
            Integer delayMs,
            Integer probabilityPercent,
            Integer maxExecutions
    ) {
        this.runtime = runtime;
        this.project = project;
        this.name = name;
        this.description = description;
        this.status = status != null ? status : ScenarioStatus.ACTIVE;
        this.pathPattern = pathPattern;
        this.httpMethod = httpMethod != null ? httpMethod.trim().toUpperCase() : null;
        this.action = action;
        this.statusCode = statusCode;
        this.delayMs = delayMs;
        this.probabilityPercent = probabilityPercent;
        this.maxExecutions = maxExecutions;
        this.executionCount = 0;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = ScenarioStatus.ACTIVE;
        }
        if (this.httpMethod != null && this.httpMethod.isBlank()) {
            this.httpMethod = null;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
        if (this.httpMethod != null && this.httpMethod.isBlank()) {
            this.httpMethod = null;
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public MockRuntime getRuntime() {
        return runtime;
    }

    public void setRuntime(MockRuntime runtime) {
        this.runtime = runtime;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ScenarioStatus getStatus() {
        return status;
    }

    public void setStatus(ScenarioStatus status) {
        this.status = status;
    }

    public String getPathPattern() {
        return pathPattern;
    }

    public void setPathPattern(String pathPattern) {
        this.pathPattern = pathPattern;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod != null && !httpMethod.isBlank() ? httpMethod.trim().toUpperCase() : null;
    }

    public ScenarioAction getAction() {
        return action;
    }

    public void setAction(ScenarioAction action) {
        this.action = action;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public Integer getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(Integer delayMs) {
        this.delayMs = delayMs;
    }

    public Integer getProbabilityPercent() {
        return probabilityPercent;
    }

    public void setProbabilityPercent(Integer probabilityPercent) {
        this.probabilityPercent = probabilityPercent;
    }

    public Integer getMaxExecutions() {
        return maxExecutions;
    }

    public void setMaxExecutions(Integer maxExecutions) {
        this.maxExecutions = maxExecutions;
    }

    public int getExecutionCount() {
        return executionCount;
    }

    public void setExecutionCount(int executionCount) {
        this.executionCount = executionCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Scenario scenario)) return false;
        return Objects.equals(id, scenario.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
