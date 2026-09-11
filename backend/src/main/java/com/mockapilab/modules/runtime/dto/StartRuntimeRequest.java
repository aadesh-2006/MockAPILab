package com.mockapilab.modules.runtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record StartRuntimeRequest(
        @Schema(description = "Custom descriptive name for the runtime instance", example = "E-Commerce Mock v1")
        @Size(max = 100, message = "Runtime name cannot exceed 100 characters")
        String name
) {
}