package com.mockapilab.modules.scenario.dto;

import com.mockapilab.modules.scenario.model.Scenario;
import com.mockapilab.modules.scenario.model.ScenarioAction;
import com.mockapilab.modules.scenario.model.ScenarioStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO representing scenario details.
 */
public record ScenarioResponse(
        UUID id,
        UUID runtimeId,
        UUID projectId,
        String name,
        String description,
        ScenarioStatus status,
        String pathPattern,
        String httpMethod,
        ScenarioAction action,
        Integer statusCode,
        Integer delayMs,
        Integer probabilityPercent,
        Integer maxExecutions,
        int executionCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static ScenarioResponse fromEntity(Scenario scenario) {
        return new ScenarioResponse(
                scenario.getId(),
                scenario.getRuntime().getId(),
                scenario.getProject().getId(),
                scenario.getName(),
                scenario.getDescription(),
                scenario.getStatus(),
                scenario.getPathPattern(),
                scenario.getHttpMethod(),
                scenario.getAction(),
                scenario.getStatusCode(),
                scenario.getDelayMs(),
                scenario.getProbabilityPercent(),
                scenario.getMaxExecutions(),
                scenario.getExecutionCount(),
                scenario.getCreatedAt(),
                scenario.getUpdatedAt()
        );
    }
}
