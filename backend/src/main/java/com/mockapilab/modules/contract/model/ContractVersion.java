package com.mockapilab.modules.contract.model;

import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
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
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * ContractVersion entity storing an immutable snapshot of a contract's normalized definition.
 */
@Entity
@Table(
        name = "contract_versions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_contract_version", columnNames = {"contract_id", "version_number"})
        }
)
public class ContractVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contract_id", nullable = false)
    private Contract contract;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 50)
    private ContractSourceType sourceType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "normalized_definition", nullable = false, columnDefinition = "jsonb")
    private NormalizedContract normalizedDefinition;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ContractVersion() {
    }

    public ContractVersion(Contract contract, int versionNumber, ContractSourceType sourceType, NormalizedContract normalizedDefinition) {
        this.contract = contract;
        this.versionNumber = versionNumber;
        this.sourceType = sourceType;
        this.normalizedDefinition = normalizedDefinition;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Contract getContract() {
        return contract;
    }

    public void setContract(Contract contract) {
        this.contract = contract;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = versionNumber;
    }

    public ContractSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(ContractSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public NormalizedContract getNormalizedDefinition() {
        return normalizedDefinition;
    }

    public void setNormalizedDefinition(NormalizedContract normalizedDefinition) {
        this.normalizedDefinition = normalizedDefinition;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContractVersion that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
