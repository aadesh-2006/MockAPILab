package com.mockapilab.modules.contract.dto;

import com.mockapilab.modules.contract.model.ContractSourceType;
import com.mockapilab.modules.contract.model.ContractVersion;
import com.mockapilab.modules.contract.model.normalized.NormalizedContract;

import java.time.Instant;
import java.util.UUID;

public record ContractVersionDetailResponse(
        UUID id,
        UUID contractId,
        int versionNumber,
        ContractSourceType sourceType,
        NormalizedContract normalizedDefinition,
        Instant createdAt
) {
    public static ContractVersionDetailResponse fromEntity(ContractVersion version) {
        return new ContractVersionDetailResponse(
                version.getId(),
                version.getContract().getId(),
                version.getVersionNumber(),
                version.getSourceType(),
                version.getNormalizedDefinition(),
                version.getCreatedAt()
        );
    }
}
