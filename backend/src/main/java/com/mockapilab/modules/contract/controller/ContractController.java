package com.mockapilab.modules.contract.controller;

import com.mockapilab.common.api.ApiResponse;
import com.mockapilab.modules.ai.dto.AiExtractContractRequest;
import com.mockapilab.modules.ai.dto.AiExtractContractResponse;
import com.mockapilab.modules.auth.security.UserPrincipal;
import com.mockapilab.modules.contract.dto.ContractDetailResponse;
import com.mockapilab.modules.contract.dto.ContractSummaryResponse;
import com.mockapilab.modules.contract.dto.ContractVersionDetailResponse;
import com.mockapilab.modules.contract.dto.ContractVersionSummaryResponse;
import com.mockapilab.modules.contract.dto.IngestContractRequest;
import com.mockapilab.modules.contract.service.ContractService;
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
 * Controller exposing API Contract ingestion and versioned retrieval endpoints.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/contracts")
@Tag(name = "Contracts", description = "API Contract ingestion and normalized schema management")
@SecurityRequirement(name = "bearerAuth")
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @PostMapping
    @Operation(summary = "Ingest API Contract", description = "Parses and normalizes an OpenAPI 3.x (JSON/YAML) document and creates version 1.")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> ingestContract(
            @PathVariable UUID projectId,
            @Valid @RequestBody IngestContractRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        ContractDetailResponse response = contractService.ingestContract(projectId, request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Contract ingested and normalized successfully", response));
    }

    @PostMapping("/ai-extract")
    @Operation(summary = "Extract Contract via Gemini AI", description = "Analyzes natural-language descriptions or Spring Boot code with Gemini AI, validates deterministically, and normalizes into a canonical API contract.")
    public ResponseEntity<ApiResponse<AiExtractContractResponse>> aiExtractContract(
            @PathVariable UUID projectId,
            @Valid @RequestBody AiExtractContractRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        AiExtractContractResponse response = contractService.extractAndIngestAiContract(projectId, request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Contract extracted and normalized via AI successfully", response));
    }

    @GetMapping
    @Operation(summary = "List Project Contracts", description = "Retrieves summary list of all contracts belonging to the project.")
    public ResponseEntity<ApiResponse<List<ContractSummaryResponse>>> listContracts(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        List<ContractSummaryResponse> response = contractService.listProjectContracts(projectId, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Project contracts retrieved successfully", response));
    }

    @GetMapping("/{contractId}")
    @Operation(summary = "Get Contract Details", description = "Retrieves contract metadata and latest version statistics.")
    public ResponseEntity<ApiResponse<ContractDetailResponse>> getContract(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        ContractDetailResponse response = contractService.getContract(projectId, contractId, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Contract details retrieved successfully", response));
    }

    @GetMapping("/{contractId}/versions")
    @Operation(summary = "List Contract Versions", description = "Retrieves all versions created for a specific contract.")
    public ResponseEntity<ApiResponse<List<ContractVersionSummaryResponse>>> listContractVersions(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        List<ContractVersionSummaryResponse> response = contractService.listContractVersions(projectId, contractId, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Contract versions retrieved successfully", response));
    }

    @GetMapping("/{contractId}/versions/{versionNumber}")
    @Operation(summary = "Get Contract Version Definition", description = "Retrieves full NormalizedContract definition for a specific version.")
    public ResponseEntity<ApiResponse<ContractVersionDetailResponse>> getContractVersion(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @PathVariable int versionNumber,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        ContractVersionDetailResponse response = contractService.getContractVersion(projectId, contractId, versionNumber, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Contract version definition retrieved successfully", response));
    }
}
