package com.mockapilab.modules.contract.drift.model;

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
import jakarta.persistence.Table;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "contract_drift_changes")
public class ContractDriftChange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private ContractDriftReport report;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 64)
    private DriftChangeType changeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false, length = 32)
    private DriftClassification classification;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 32)
    private DriftSeverity severity;

    @Column(name = "path", length = 255)
    private String path;

    @Column(name = "method", length = 16)
    private String method;

    @Column(name = "location", length = 255)
    private String location;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    public ContractDriftChange() {
    }

    public ContractDriftChange(
            DriftChangeType changeType,
            DriftClassification classification,
            DriftSeverity severity,
            String path,
            String method,
            String location,
            String oldValue,
            String newValue,
            String message
    ) {
        this.changeType = changeType;
        this.classification = classification;
        this.severity = severity;
        this.path = path;
        this.method = method;
        this.location = location;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.message = message;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public ContractDriftReport getReport() {
        return report;
    }

    public void setReport(ContractDriftReport report) {
        this.report = report;
    }

    public DriftChangeType getChangeType() {
        return changeType;
    }

    public void setChangeType(DriftChangeType changeType) {
        this.changeType = changeType;
    }

    public DriftClassification getClassification() {
        return classification;
    }

    public void setClassification(DriftClassification classification) {
        this.classification = classification;
    }

    public DriftSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(DriftSeverity severity) {
        this.severity = severity;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContractDriftChange that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
