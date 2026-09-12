package com.mockapilab.modules.scenario.controller;

import com.mockapilab.common.api.ApiResponse;
import com.mockapilab.modules.auth.security.UserPrincipal;
import com.mockapilab.modules.scenario.dto.ScenarioRequest;
import com.mockapilab.modules.scenario.dto.ScenarioResponse;
import com.mockapilab.modules.scenario.service.ScenarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated REST controller for managing mock runtime scenarios and failure injection rules.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runtimes/{runtimeId}/scenarios")
@Tag(name = "Scenarios", description = "Mock runtime scenario configuration and failure injector APIs")
public class ScenarioController {

    private final ScenarioService scenarioService;

    public ScenarioController(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    @PostMapping
    @Operation(summary = "Create a new scenario for a mock runtime")
    public ResponseEntity<ApiResponse<ScenarioResponse>> createScenario(
            @PathVariable UUID projectId,
            @PathVariable UUID runtimeId,
            @Valid @RequestBody ScenarioRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        ScenarioResponse response = scenarioService.createScenario(projectId, runtimeId, request, principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Scenario created successfully", response));
    }

    @GetMapping
    @Operation(summary = "List all scenarios for a mock runtime")
    public ResponseEntity<ApiResponse<List<ScenarioResponse>>> getScenarios(
            @PathVariable UUID projectId,
            @PathVariable UUID runtimeId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        List<ScenarioResponse> response = scenarioService.getScenarios(projectId, runtimeId, principal);
        return ResponseEntity.ok(ApiResponse.success("Scenarios retrieved successfully", response));
    }

    @GetMapping("/{scenarioId}")
    @Operation(summary = "Get scenario details by ID")
    public ResponseEntity<ApiResponse<ScenarioResponse>> getScenario(
            @PathVariable UUID projectId,
            @PathVariable UUID runtimeId,
            @PathVariable UUID scenarioId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        ScenarioResponse response = scenarioService.getScenario(projectId, runtimeId, scenarioId, principal);
        return ResponseEntity.ok(ApiResponse.success("Scenario retrieved successfully", response));
    }

    @PutMapping("/{scenarioId}")
    @Operation(summary = "Update an existing scenario")
    public ResponseEntity<ApiResponse<ScenarioResponse>> updateScenario(
            @PathVariable UUID projectId,
            @PathVariable UUID runtimeId,
            @PathVariable UUID scenarioId,
            @Valid @RequestBody ScenarioRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        ScenarioResponse response = scenarioService.updateScenario(projectId, runtimeId, scenarioId, request, principal);
        return ResponseEntity.ok(ApiResponse.success("Scenario updated successfully", response));
    }

    @DeleteMapping("/{scenarioId}")
    @Operation(summary = "Delete a scenario")
    public ResponseEntity<Void> deleteScenario(
            @PathVariable UUID projectId,
            @PathVariable UUID runtimeId,
            @PathVariable UUID scenarioId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        scenarioService.deleteScenario(projectId, runtimeId, scenarioId, principal);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{scenarioId}/enable")
    @Operation(summary = "Enable an existing scenario")
    public ResponseEntity<ApiResponse<ScenarioResponse>> enableScenario(
            @PathVariable UUID projectId,
            @PathVariable UUID runtimeId,
            @PathVariable UUID scenarioId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        ScenarioResponse response = scenarioService.enableScenario(projectId, runtimeId, scenarioId, principal);
        return ResponseEntity.ok(ApiResponse.success("Scenario enabled successfully", response));
    }

    @PostMapping("/{scenarioId}/disable")
    @Operation(summary = "Disable an existing scenario")
    public ResponseEntity<ApiResponse<ScenarioResponse>> disableScenario(
            @PathVariable UUID projectId,
            @PathVariable UUID runtimeId,
            @PathVariable UUID scenarioId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        ScenarioResponse response = scenarioService.disableScenario(projectId, runtimeId, scenarioId, principal);
        return ResponseEntity.ok(ApiResponse.success("Scenario disabled successfully", response));
    }
}
