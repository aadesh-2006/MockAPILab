package com.mockapilab.modules.contract.dto;

import com.mockapilab.modules.contract.model.Contract;
import com.mockapilab.modules.contract.model.ContractSourceType;
import com.mockapilab.modules.contract.model.ContractVersion;

import java.time.Instant;
import java.util.UUID;

public record ContractDetailResponse(
        UUID id,
        UUID projectId,
        String name,
        String description,
        int latestVersion,
        ContractSourceType latestSourceType,
        int totalEndpoints,
        int totalSchemas,
        Instant createdAt,
        Instant updatedAt
) {
    public static ContractDetailResponse fromEntity(Contract contract, ContractVersion latestVersion) {
        int totalEndpoints = 0;
        int totalSchemas = 0;
        ContractSourceType sourceType = null;
        int versionNumber = 1;

        if (latestVersion != null) {
            versionNumber = latestVersion.getVersionNumber();
            sourceType = latestVersion.getSourceType();
            if (latestVersion.getNormalizedDefinition() != null) {
                totalEndpoints = latestVersion.getNormalizedDefinition().endpoints() != null
                        ? latestVersion.getNormalizedDefinition().endpoints().size()
                        : 0;
                totalSchemas = latestVersion.getNormalizedDefinition().schemas() != null
                        ? latestVersion.getNormalizedDefinition().schemas().size()
                        : 0;
            }
        }

        return new ContractDetailResponse(
                contract.getId(),
                contract.getProject().getId(),
                contract.getName(),
                contract.getDescription(),
                versionNumber,
                sourceType,
                totalEndpoints,
                totalSchemas,
                contract.getCreatedAt(),
                contract.getUpdatedAt()
        );
    }
}
