package com.mockapilab.modules.runtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record GenerateDataRequest(
        @Schema(description = "Target collection path to generate entities for", example = "/pets")
        @NotBlank(message = "Collection path is required")
        String collection,

        @Schema(description = "Number of mock entities to generate (1-100)", example = "10", defaultValue = "5")
        @Min(value = 1, message = "Count must be at least 1")
        @Max(value = 100, message = "Count cannot exceed 100")
        Integer count,

        @Schema(description = "Deterministic seed for reproducible generation. If omitted, a seed is generated and returned.", example = "42")
        Long seed
) {
    public int getEffectiveCount() {
        return count != null && count > 0 ? count : 5;
    }
}
