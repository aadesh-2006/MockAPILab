package com.mockapilab.modules.contract.drift.controller;

import com.mockapilab.common.api.ApiResponse;
import com.mockapilab.modules.auth.security.UserPrincipal;
import com.mockapilab.modules.contract.drift.dto.DriftAnalysisRequest;
import com.mockapilab.modules.contract.drift.dto.DriftReportResponse;
import com.mockapilab.modules.contract.drift.service.ContractDriftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Controller exposing deterministic Contract Drift Detection endpoints.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/contracts/{contractId}/drift")
@Tag(name = "Contract Drift Detection", description = "Deterministic analysis and reporting of contract changes across versions")
@SecurityRequirement(name = "bearerAuth")
public class ContractDriftController {

    private final ContractDriftService driftService;

    public ContractDriftController(ContractDriftService driftService) {
        this.driftService = driftService;
    }

    @PostMapping
    @Operation(summary = "Analyze Contract Drift", description = "Compares two immutable normalized contract versions, classifies breaking/non-breaking/informational changes, and persists an audit report.")
    public ResponseEntity<ApiResponse<DriftReportResponse>> analyzeDrift(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @Valid @RequestBody DriftAnalysisRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        DriftReportResponse response = driftService.analyzeDrift(projectId, contractId, request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Contract drift analysis completed successfully", response));
    }

    @GetMapping
    @Operation(summary = "List Contract Drift Reports", description = "Retrieves all historical drift analysis reports for the contract.")
    public ResponseEntity<ApiResponse<List<DriftReportResponse>>> listDriftReports(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        List<DriftReportResponse> response = driftService.listContractDriftReports(projectId, contractId, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Contract drift reports retrieved successfully", response));
    }

    @GetMapping("/{reportId}")
    @Operation(summary = "Get Contract Drift Report", description = "Retrieves a specific drift report including its full list of structural changes.")
    public ResponseEntity<ApiResponse<DriftReportResponse>> getDriftReport(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @PathVariable UUID reportId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        DriftReportResponse response = driftService.getContractDriftReport(projectId, contractId, reportId, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Contract drift report retrieved successfully", response));
    }
}
