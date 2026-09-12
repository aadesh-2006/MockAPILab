package com.mockapilab.modules.contract.drift.service;

import com.mockapilab.common.exception.ForbiddenException;
import com.mockapilab.common.exception.ResourceNotFoundException;
import com.mockapilab.modules.contract.drift.dto.DriftAnalysisRequest;
import com.mockapilab.modules.contract.drift.dto.DriftReportResponse;
import com.mockapilab.modules.contract.drift.engine.ContractDiffEngine;
import com.mockapilab.modules.contract.drift.model.ContractDriftChange;
import com.mockapilab.modules.contract.drift.model.ContractDriftReport;
import com.mockapilab.modules.contract.drift.repository.ContractDriftReportRepository;
import com.mockapilab.modules.contract.model.Contract;
import com.mockapilab.modules.contract.model.ContractVersion;
import com.mockapilab.modules.contract.repository.ContractRepository;
import com.mockapilab.modules.contract.repository.ContractVersionRepository;
import com.mockapilab.modules.project.model.Project;
import com.mockapilab.modules.project.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service managing deterministic contract drift analysis and historical drift reports.
 */
@Service
public class ContractDriftService {

    private final ContractDriftReportRepository driftReportRepository;
    private final ContractRepository contractRepository;
    private final ContractVersionRepository contractVersionRepository;
    private final ProjectRepository projectRepository;
    private final ContractDiffEngine diffEngine;

    public ContractDriftService(
            ContractDriftReportRepository driftReportRepository,
            ContractRepository contractRepository,
            ContractVersionRepository contractVersionRepository,
            ProjectRepository projectRepository,
            ContractDiffEngine diffEngine
    ) {
        this.driftReportRepository = driftReportRepository;
        this.contractRepository = contractRepository;
        this.contractVersionRepository = contractVersionRepository;
        this.projectRepository = projectRepository;
        this.diffEngine = diffEngine;
    }

    @Transactional
    public DriftReportResponse analyzeDrift(UUID projectId, UUID contractId, DriftAnalysisRequest request, UUID currentUserId) {
        Project project = verifyProjectOwnership(projectId, currentUserId);

        Contract contract = contractRepository.findByIdAndProjectId(contractId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Contract not found with id: " + contractId));

        ContractVersion fromVersion = contractVersionRepository.findByContractIdAndVersionNumber(contractId, request.fromVersion())
                .orElseThrow(() -> new ResourceNotFoundException("Base contract version " + request.fromVersion() + " not found for contract " + contractId));

        ContractVersion toVersion = contractVersionRepository.findByContractIdAndVersionNumber(contractId, request.toVersion())
                .orElseThrow(() -> new ResourceNotFoundException("Target contract version " + request.toVersion() + " not found for contract " + contractId));

        ContractDiffEngine.DiffResult diffResult = diffEngine.diff(
                fromVersion.getNormalizedDefinition(),
                toVersion.getNormalizedDefinition()
        );

        ContractDriftReport report = new ContractDriftReport(
                project,
                contract,
                fromVersion,
                toVersion,
                fromVersion.getVersionNumber(),
                toVersion.getVersionNumber(),
                diffResult.breakingCount(),
                diffResult.nonBreakingCount(),
                diffResult.informationalCount(),
                diffResult.overallSeverity()
        );

        for (ContractDriftChange change : diffResult.changes()) {
            report.addChange(change);
        }

        ContractDriftReport savedReport = driftReportRepository.save(report);
        return DriftReportResponse.fromEntity(savedReport);
    }

    @Transactional(readOnly = true)
    public List<DriftReportResponse> listContractDriftReports(UUID projectId, UUID contractId, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        if (!contractRepository.existsByIdAndProjectId(contractId, projectId)) {
            throw new ResourceNotFoundException("Contract not found with id: " + contractId);
        }

        return driftReportRepository.findAllByContractIdOrderByCreatedAtDesc(contractId)
                .stream()
                .map(DriftReportResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public DriftReportResponse getContractDriftReport(UUID projectId, UUID contractId, UUID reportId, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        if (!contractRepository.existsByIdAndProjectId(contractId, projectId)) {
            throw new ResourceNotFoundException("Contract not found with id: " + contractId);
        }

        ContractDriftReport report = driftReportRepository.findByIdAndContractId(reportId, contractId)
                .orElseThrow(() -> new ResourceNotFoundException("Drift report not found with id: " + reportId));

        return DriftReportResponse.fromEntity(report);
    }

    private Project verifyProjectOwnership(UUID projectId, UUID currentUserId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));

        if (!project.getOwner().getId().equals(currentUserId)) {
            throw new ForbiddenException("Access denied: You do not have permission to access project " + projectId);
        }

        return project;
    }
}
