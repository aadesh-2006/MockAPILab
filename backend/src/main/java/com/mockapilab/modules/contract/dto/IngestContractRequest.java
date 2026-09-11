package com.mockapilab.modules.contract.dto;

import com.mockapilab.modules.contract.model.ContractSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IngestContractRequest(
        @NotBlank(message = "Contract name is required")
        @Size(max = 100, message = "Contract name cannot exceed 100 characters")
        String name,

        @Size(max = 500, message = "Contract description cannot exceed 500 characters")
        String description,

        @NotBlank(message = "Contract content (OpenAPI JSON/YAML) is required")
        String content,

        ContractSourceType sourceType
) {
    public ContractSourceType getEffectiveSourceType() {
        return sourceType != null ? sourceType : ContractSourceType.OPENAPI;
    }
}
