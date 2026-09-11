package com.mockapilab.modules.runtime.controller;

import com.mockapilab.common.api.ApiResponse;
import com.mockapilab.modules.auth.security.UserPrincipal;
import com.mockapilab.modules.runtime.dto.GenerateDataRequest;
import com.mockapilab.modules.runtime.dto.GenerateDataResponse;
import com.mockapilab.modules.runtime.dto.RuntimeResponse;
import com.mockapilab.modules.runtime.dto.RuntimeStatusResponse;
import com.mockapilab.modules.runtime.dto.StartRuntimeRequest;
import com.mockapilab.modules.runtime.service.RuntimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Controller exposing endpoints for managing mock runtime server lifecycles and realistic data generation.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@Tag(name = "Mock Runtimes", description = "Dynamic mock runtime lifecycle and deterministic data generation")
@SecurityRequirement(name = "bearerAuth")
public class RuntimeController {

    private final RuntimeService runtimeService;

    public RuntimeController(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @PostMapping("/contracts/{contractId}/versions/{versionNumber}/runtime")
    @Operation(summary = "Start Mock Runtime", description = "Compiles the contract routes and starts an isolated in-process mock server.")
    public ResponseEntity<ApiResponse<RuntimeResponse>> startRuntime(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @PathVariable int versionNumber,
            @Valid @RequestBody(required = false) StartRuntimeRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        RuntimeResponse response = runtimeService.startRuntime(projectId, contractId, versionNumber, request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Mock runtime started successfully", response));
    }

    @GetMapping("/runtimes")
    @Operation(summary = "List Project Runtimes", description = "Retrieves all runtime instances created in the project.")
    public ResponseEntity<ApiResponse<List<RuntimeResponse>>> listRuntimes(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        List<RuntimeResponse> response = runtimeService.listProjectRuntimes(projectId, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Project runtimes retrieved successfully", response));
    }

    @GetMapping("/runtimes/{runtimeId}")
    @Operation(summary = "Get Runtime Details", description = "Retrieves details of a specific runtime instance.")
    public ResponseEntity<ApiResponse<RuntimeResponse>> getRuntime(
            @PathVariable UUID projectId,
            @PathVariable UUID runtimeId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        RuntimeResponse response = runtimeService.getRuntime(projectId, runtimeId, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Runtime details retrieved successfully", response));
    }

    @GetMapping("/runtimes/{runtimeId}/status")
    @Operation(summary = "Get Runtime Status", description = "Retrieves live status, state statistics, and uptime of an active runtime.")
    public ResponseEntity<ApiResponse<RuntimeStatusResponse>> getRuntimeStatus(
            @PathVariable UUID projectId,
            @PathVariable UUID runtimeId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        RuntimeStatusResponse response = runtimeService.getRuntimeStatus(projectId, runtimeId, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Runtime status retrieved successfully", response));
    }

    @PostMapping("/runtimes/{runtimeId}/data/generate")
    @Operation(summary = "Generate Mock Collection Data", description = "Generates realistic, deterministic mock entities for a specific collection and populates the runtime state store.")
    public ResponseEntity<ApiResponse<GenerateDataResponse>> generateMockData(
            @PathVariable UUID projectId,
            @PathVariable UUID runtimeId,
            @Valid @RequestBody GenerateDataRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        GenerateDataResponse response = runtimeService.generateMockData(projectId, runtimeId, request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Mock collection data generated successfully", response));
    }

    @PostMapping("/runtimes/{runtimeId}/stop")
    @Operation(summary = "Stop Mock Runtime", description = "Stops an active mock runtime instance.")
    public ResponseEntity<ApiResponse<RuntimeResponse>> stopRuntime(
            @PathVariable UUID projectId,
            @PathVariable UUID runtimeId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        RuntimeResponse response = runtimeService.stopRuntime(projectId, runtimeId, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Mock runtime stopped successfully", response));
    }

    @DeleteMapping("/runtimes/{runtimeId}")
    @Operation(summary = "Delete Mock Runtime", description = "Stops, clears in-memory state, and deletes the runtime instance.")
    public ResponseEntity<ApiResponse<Void>> deleteRuntime(
            @PathVariable UUID projectId,
            @PathVariable UUID runtimeId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        runtimeService.deleteRuntime(projectId, runtimeId, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Mock runtime deleted successfully", null));
    }
}
