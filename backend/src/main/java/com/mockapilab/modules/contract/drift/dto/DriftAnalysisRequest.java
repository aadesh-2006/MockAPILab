package com.mockapilab.modules.contract.drift.dto;

import jakarta.validation.constraints.Min;

public record DriftAnalysisRequest(
        @Min(value = 1, message = "fromVersion must be at least 1")
        int fromVersion,

        @Min(value = 1, message = "toVersion must be at least 1")
        int toVersion
) {
}
