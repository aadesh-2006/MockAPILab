package com.mockapilab.modules.ai.dto;

import com.mockapilab.modules.contract.dto.ContractDetailResponse;
import java.util.UUID;

/**
 * Response payload returned after successful AI contract extraction and normalization.
 */
public record AiExtractContractResponse(
        UUID contractId,
        String name,
        String description,
        int version,
        String sourceType,
        int extractedEndpointsCount,
        int extractedSchemasCount,
        String candidateTitle,
        ContractDetailResponse contract
) {
}