package com.mockapilab.modules.contract.service;

import com.mockapilab.common.exception.ForbiddenException;
import com.mockapilab.common.exception.ResourceNotFoundException;
import com.mockapilab.modules.contract.dto.ContractDetailResponse;
import com.mockapilab.modules.contract.dto.ContractSummaryResponse;
import com.mockapilab.modules.contract.dto.ContractVersionDetailResponse;
import com.mockapilab.modules.contract.dto.ContractVersionSummaryResponse;
import com.mockapilab.modules.contract.dto.IngestContractRequest;
import com.mockapilab.modules.contract.model.Contract;
import com.mockapilab.modules.contract.model.ContractVersion;
import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.parser.OpenApiContractParser;
import com.mockapilab.modules.contract.repository.ContractRepository;
import com.mockapilab.modules.contract.repository.ContractVersionRepository;
import com.mockapilab.modules.project.model.Project;
import com.mockapilab.modules.project.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service managing contract ingestion, normalization, and versioned retrieval.
 */
@Service
public class ContractService {

    private final ContractRepository contractRepository;
    private final ContractVersionRepository contractVersionRepository;
    private final ProjectRepository projectRepository;
    private final OpenApiContractParser openApiContractParser;

    public ContractService(
            ContractRepository contractRepository,
            ContractVersionRepository contractVersionRepository,
            ProjectRepository projectRepository,
            OpenApiContractParser openApiContractParser
    ) {
        this.contractRepository = contractRepository;
        this.contractVersionRepository = contractVersionRepository;
        this.projectRepository = projectRepository;
        this.openApiContractParser = openApiContractParser;
    }

    @Transactional
    public ContractDetailResponse ingestContract(UUID projectId, IngestContractRequest request, UUID currentUserId) {
        Project project = verifyProjectOwnership(projectId, currentUserId);

        NormalizedContract normalizedContract = openApiContractParser.parse(request.content());

        Contract contract = new Contract(project, request.name().trim(), request.description());
        Contract savedContract = contractRepository.save(contract);

        ContractVersion version1 = new ContractVersion(
                savedContract,
                1,
                request.getEffectiveSourceType(),
                normalizedContract
        );
        savedContract.addVersion(version1);
        ContractVersion savedVersion = contractVersionRepository.save(version1);

        return ContractDetailResponse.fromEntity(savedContract, savedVersion);
    }

    @Transactional(readOnly = true)
    public List<ContractSummaryResponse> listProjectContracts(UUID projectId, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        return contractRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(contract -> {
                    int latestVersion = contractVersionRepository.findFirstByContractIdOrderByVersionNumberDesc(contract.getId())
                            .map(ContractVersion::getVersionNumber)
                            .orElse(1);
                    return ContractSummaryResponse.fromEntity(contract, latestVersion);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ContractDetailResponse getContract(UUID projectId, UUID contractId, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        Contract contract = contractRepository.findByIdAndProjectId(contractId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Contract not found with id: " + contractId));

        ContractVersion latestVersion = contractVersionRepository.findFirstByContractIdOrderByVersionNumberDesc(contractId)
                .orElse(null);

        return ContractDetailResponse.fromEntity(contract, latestVersion);
    }

    @Transactional(readOnly = true)
    public List<ContractVersionSummaryResponse> listContractVersions(UUID projectId, UUID contractId, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        if (!contractRepository.existsByIdAndProjectId(contractId, projectId)) {
            throw new ResourceNotFoundException("Contract not found with id: " + contractId);
        }

        return contractVersionRepository.findAllByContractIdOrderByVersionNumberDesc(contractId)
                .stream()
                .map(ContractVersionSummaryResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public ContractVersionDetailResponse getContractVersion(UUID projectId, UUID contractId, int versionNumber, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        if (!contractRepository.existsByIdAndProjectId(contractId, projectId)) {
            throw new ResourceNotFoundException("Contract not found with id: " + contractId);
        }

        ContractVersion version = contractVersionRepository.findByContractIdAndVersionNumber(contractId, versionNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Contract version " + versionNumber + " not found for contract " + contractId));

        return ContractVersionDetailResponse.fromEntity(version);
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
