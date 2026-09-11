package com.mockapilab.modules.contract.dto;

import com.mockapilab.modules.contract.model.Contract;
import com.mockapilab.modules.contract.model.ContractVersion;

import java.time.Instant;
import java.util.UUID;

public record ContractSummaryResponse(
        UUID id,
        UUID projectId,
        String name,
        String description,
        int latestVersion,
        Instant createdAt,
        Instant updatedAt
) {
    public static ContractSummaryResponse fromEntity(Contract contract, int latestVersion) {
        return new ContractSummaryResponse(
                contract.getId(),
                contract.getProject().getId(),
                contract.getName(),
                contract.getDescription(),
                latestVersion,
                contract.getCreatedAt(),
                contract.getUpdatedAt()
        );
    }
}
