package com.mockapilab.modules.runtime.model;

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
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable entity tracking asynchronous mock collection data generation jobs.
 */
@Entity
@Table(name = "generation_jobs")
public class GenerationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "runtime_id", nullable = false)
    private MockRuntime runtime;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "collection_path", nullable = false, length = 255)
    private String collectionPath;

    @Column(name = "entity_count", nullable = false)
    private int entityCount;

    @Column(name = "requested_seed")
    private Long requestedSeed;

    @Column(name = "effective_seed")
    private Long effectiveSeed;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private GenerationJobStatus status;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public GenerationJob() {
    }

    public GenerationJob(MockRuntime runtime, UUID projectId, String collectionPath, int entityCount, Long requestedSeed) {
        this.runtime = runtime;
        this.projectId = projectId;
        this.collectionPath = collectionPath;
        this.entityCount = entityCount;
        this.requestedSeed = requestedSeed;
        this.status = GenerationJobStatus.QUEUED;
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.status == null) {
            this.status = GenerationJobStatus.QUEUED;
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

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public String getCollectionPath() {
        return collectionPath;
    }

    public void setCollectionPath(String collectionPath) {
        this.collectionPath = collectionPath;
    }

    public int getEntityCount() {
        return entityCount;
    }

    public void setEntityCount(int entityCount) {
        this.entityCount = entityCount;
    }

    public Long getRequestedSeed() {
        return requestedSeed;
    }

    public void setRequestedSeed(Long requestedSeed) {
        this.requestedSeed = requestedSeed;
    }

    public Long getEffectiveSeed() {
        return effectiveSeed;
    }

    public void setEffectiveSeed(Long effectiveSeed) {
        this.effectiveSeed = effectiveSeed;
    }

    public GenerationJobStatus getStatus() {
        return status;
    }

    public void setStatus(GenerationJobStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GenerationJob that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
