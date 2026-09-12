package com.mockapilab.modules.contract.drift.model;

import com.mockapilab.modules.contract.model.Contract;
import com.mockapilab.modules.contract.model.ContractVersion;
import com.mockapilab.modules.project.model.Project;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "contract_drift_reports")
public class ContractDriftReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contract_id", nullable = false)
    private Contract contract;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_version_id", nullable = false)
    private ContractVersion fromVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_version_id", nullable = false)
    private ContractVersion toVersion;

    @Column(name = "from_version_number", nullable = false)
    private int fromVersionNumber;

    @Column(name = "to_version_number", nullable = false)
    private int toVersionNumber;

    @Column(name = "breaking_change_count", nullable = false)
    private int breakingChangeCount;

    @Column(name = "non_breaking_change_count", nullable = false)
    private int nonBreakingChangeCount;

    @Column(name = "informational_change_count", nullable = false)
    private int informationalChangeCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_severity", nullable = false, length = 32)
    private DriftSeverity overallSeverity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ContractDriftChange> changes = new ArrayList<>();

    public ContractDriftReport() {
    }

    public ContractDriftReport(
            Project project,
            Contract contract,
            ContractVersion fromVersion,
            ContractVersion toVersion,
            int fromVersionNumber,
            int toVersionNumber,
            int breakingChangeCount,
            int nonBreakingChangeCount,
            int informationalChangeCount,
            DriftSeverity overallSeverity
    ) {
        this.project = project;
        this.contract = contract;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.fromVersionNumber = fromVersionNumber;
        this.toVersionNumber = toVersionNumber;
        this.breakingChangeCount = breakingChangeCount;
        this.nonBreakingChangeCount = nonBreakingChangeCount;
        this.informationalChangeCount = informationalChangeCount;
        this.overallSeverity = overallSeverity;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public void addChange(ContractDriftChange change) {
        this.changes.add(change);
        change.setReport(this);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public Contract getContract() {
        return contract;
    }

    public void setContract(Contract contract) {
        this.contract = contract;
    }

    public ContractVersion getFromVersion() {
        return fromVersion;
    }

    public void setFromVersion(ContractVersion fromVersion) {
        this.fromVersion = fromVersion;
    }

    public ContractVersion getToVersion() {
        return toVersion;
    }

    public void setToVersion(ContractVersion toVersion) {
        this.toVersion = toVersion;
    }

    public int getFromVersionNumber() {
        return fromVersionNumber;
    }

    public void setFromVersionNumber(int fromVersionNumber) {
        this.fromVersionNumber = fromVersionNumber;
    }

    public int getToVersionNumber() {
        return toVersionNumber;
    }

    public void setToVersionNumber(int toVersionNumber) {
        this.toVersionNumber = toVersionNumber;
    }

    public int getBreakingChangeCount() {
        return breakingChangeCount;
    }

    public void setBreakingChangeCount(int breakingChangeCount) {
        this.breakingChangeCount = breakingChangeCount;
    }

    public int getNonBreakingChangeCount() {
        return nonBreakingChangeCount;
    }

    public void setNonBreakingChangeCount(int nonBreakingChangeCount) {
        this.nonBreakingChangeCount = nonBreakingChangeCount;
    }

    public int getInformationalChangeCount() {
        return informationalChangeCount;
    }

    public void setInformationalChangeCount(int informationalChangeCount) {
        this.informationalChangeCount = informationalChangeCount;
    }

    public DriftSeverity getOverallSeverity() {
        return overallSeverity;
    }

    public void setOverallSeverity(DriftSeverity overallSeverity) {
        this.overallSeverity = overallSeverity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<ContractDriftChange> getChanges() {
        return changes;
    }

    public void setChanges(List<ContractDriftChange> changes) {
        this.changes = changes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContractDriftReport that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
