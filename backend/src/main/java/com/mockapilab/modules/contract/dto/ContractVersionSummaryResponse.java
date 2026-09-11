package com.mockapilab.modules.contract.dto;

import com.mockapilab.modules.contract.model.ContractSourceType;
import com.mockapilab.modules.contract.model.ContractVersion;

import java.time.Instant;
import java.util.UUID;

public record ContractVersionSummaryResponse(
        UUID id,
        UUID contractId,
        int versionNumber,
        ContractSourceType sourceType,
        int totalEndpoints,
        int totalSchemas,
        Instant createdAt
) {
    public static ContractVersionSummaryResponse fromEntity(ContractVersion version) {
        int endpoints = 0;
        int schemas = 0;
        if (version.getNormalizedDefinition() != null) {
            endpoints = version.getNormalizedDefinition().endpoints() != null
                    ? version.getNormalizedDefinition().endpoints().size()
                    : 0;
            schemas = version.getNormalizedDefinition().schemas() != null
                    ? version.getNormalizedDefinition().schemas().size()
                    : 0;
        }

        return new ContractVersionSummaryResponse(
                version.getId(),
                version.getContract().getId(),
                version.getVersionNumber(),
                version.getSourceType(),
                endpoints,
                schemas,
                version.getCreatedAt()
        );
    }
}
