package com.mockapilab.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request payload for AI-assisted contract extraction.
 */
public record AiExtractContractRequest(
        @NotBlank(message = "Input content cannot be empty")
        @Size(max = 50000, message = "Input content cannot exceed 50,000 characters")
        String input,

        @NotNull(message = "Input type must be specified (DESCRIPTION or SPRING_BOOT_CODE)")
        ExtractionInputType inputType,

        String name,
        String description
) {
}