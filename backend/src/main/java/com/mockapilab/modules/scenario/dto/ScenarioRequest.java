package com.mockapilab.modules.scenario.dto;

import com.mockapilab.modules.scenario.model.ScenarioAction;
import com.mockapilab.modules.scenario.model.ScenarioStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request payload for creating or updating a scenario.
 */
public record ScenarioRequest(
        @NotBlank(message = "Scenario name is required")
        @Size(max = 100, message = "Scenario name must not exceed 100 characters")
        String name,

        @Size(max = 500, message = "Description must not exceed 500 characters")
        String description,

        ScenarioStatus status,

        @NotBlank(message = "Path pattern is required")
        @Size(max = 255, message = "Path pattern must not exceed 255 characters")
        String pathPattern,

        @Size(max = 16, message = "HTTP method must not exceed 16 characters")
        String httpMethod,

        @NotNull(message = "Scenario action is required")
        ScenarioAction action,

        @Min(value = 100, message = "Status code must be between 100 and 599")
        @Max(value = 599, message = "Status code must be between 100 and 599")
        Integer statusCode,

        @Min(value = 0, message = "Delay must be at least 0 ms")
        @Max(value = 30000, message = "Delay must not exceed 30000 ms (30 seconds)")
        Integer delayMs,

        @Min(value = 0, message = "Probability percent must be between 0 and 100")
        @Max(value = 100, message = "Probability percent must be between 0 and 100")
        Integer probabilityPercent,

        @Min(value = 1, message = "Max executions must be at least 1")
        Integer maxExecutions
) {
}
