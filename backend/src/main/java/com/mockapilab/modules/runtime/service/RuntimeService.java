package com.mockapilab.modules.runtime.service;

import com.mockapilab.common.exception.ForbiddenException;
import com.mockapilab.common.exception.ResourceNotFoundException;
import com.mockapilab.modules.contract.model.Contract;
import com.mockapilab.modules.contract.model.ContractVersion;
import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import com.mockapilab.modules.contract.model.normalized.NormalizedResponse;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.contract.repository.ContractRepository;
import com.mockapilab.modules.contract.repository.ContractVersionRepository;
import com.mockapilab.modules.project.model.Project;
import com.mockapilab.modules.project.repository.ProjectRepository;
import com.mockapilab.modules.runtime.dto.GenerateDataRequest;
import com.mockapilab.modules.runtime.dto.GenerateDataResponse;
import com.mockapilab.modules.runtime.dto.RuntimeResponse;
import com.mockapilab.modules.runtime.dto.RuntimeStatusResponse;
import com.mockapilab.modules.runtime.dto.StartRuntimeRequest;
import com.mockapilab.modules.runtime.engine.CompiledRoute;
import com.mockapilab.modules.runtime.engine.RouteCompiler;
import com.mockapilab.modules.runtime.engine.RuntimeInstance;
import com.mockapilab.modules.runtime.engine.RuntimeRegistry;
import com.mockapilab.modules.runtime.generation.MockDataGenerator;
import com.mockapilab.modules.runtime.model.MockRuntime;
import com.mockapilab.modules.runtime.model.MockRuntimeStatus;
import com.mockapilab.modules.runtime.repository.MockRuntimeRepository;
import com.mockapilab.modules.runtime.state.RuntimeStateStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service managing lifecycle operations and explicit data generation for dynamic mock runtimes.
 */
@Service
public class RuntimeService {

    private final MockRuntimeRepository runtimeRepository;
    private final ProjectRepository projectRepository;
    private final ContractRepository contractRepository;
    private final ContractVersionRepository contractVersionRepository;
    private final RuntimeRegistry runtimeRegistry;
    private final RuntimeStateStore stateStore;
    private final RouteCompiler routeCompiler;
    private final MockDataGenerator mockDataGenerator;

    public RuntimeService(
            MockRuntimeRepository runtimeRepository,
            ProjectRepository projectRepository,
            ContractRepository contractRepository,
            ContractVersionRepository contractVersionRepository,
            RuntimeRegistry runtimeRegistry,
            RuntimeStateStore stateStore,
            RouteCompiler routeCompiler,
            MockDataGenerator mockDataGenerator
    ) {
        this.runtimeRepository = runtimeRepository;
        this.projectRepository = projectRepository;
        this.contractRepository = contractRepository;
        this.contractVersionRepository = contractVersionRepository;
        this.runtimeRegistry = runtimeRegistry;
        this.stateStore = stateStore;
        this.routeCompiler = routeCompiler;
        this.mockDataGenerator = mockDataGenerator;
    }

    @Transactional
    public RuntimeResponse startRuntime(UUID projectId, UUID contractId, int versionNumber, StartRuntimeRequest request, UUID currentUserId) {
        Project project = verifyProjectOwnership(projectId, currentUserId);

        Contract contract = contractRepository.findByIdAndProjectId(contractId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Contract not found with id: " + contractId));

        ContractVersion version = contractVersionRepository.findByContractIdAndVersionNumber(contractId, versionNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Contract version " + versionNumber + " not found for contract " + contractId));

        String runtimeName = (request != null && request.name() != null && !request.name().isBlank())
                ? request.name().trim()
                : contract.getName() + " v" + version.getVersionNumber() + " Runtime";

        MockRuntime runtime = new MockRuntime(project, version, runtimeName, MockRuntimeStatus.RUNNING);
        runtime.setStartedAt(Instant.now());
        MockRuntime savedRuntime = runtimeRepository.save(runtime);

        // Compile routes and register in in-memory registry
        NormalizedContract contractDef = version.getNormalizedDefinition();
        List<CompiledRoute> compiledRoutes = routeCompiler.compile(contractDef);
        RuntimeInstance instance = new RuntimeInstance(
                savedRuntime.getId(),
                projectId,
                version.getId(),
                contractDef,
                compiledRoutes,
                savedRuntime.getStartedAt()
        );
        runtimeRegistry.register(instance);
        stateStore.clearRuntime(savedRuntime.getId());

        return mapToResponse(savedRuntime);
    }

    @Transactional(readOnly = true)
    public List<RuntimeResponse> listProjectRuntimes(UUID projectId, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        return runtimeRepository.findByProjectId(projectId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RuntimeResponse getRuntime(UUID projectId, UUID runtimeId, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        MockRuntime runtime = runtimeRepository.findByIdAndProjectId(runtimeId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Runtime not found with id: " + runtimeId));

        return mapToResponse(runtime);
    }

    @Transactional(readOnly = true)
    public RuntimeStatusResponse getRuntimeStatus(UUID projectId, UUID runtimeId, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        MockRuntime runtime = runtimeRepository.findByIdAndProjectId(runtimeId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Runtime not found with id: " + runtimeId));

        int endpointsCount = countEndpoints(runtime);
        int storedEntities = stateStore.getEntityCount(runtime.getId());
        int collectionsCount = stateStore.getCollectionCount(runtime.getId());

        long uptimeSeconds = 0;
        if (runtime.getStatus() == MockRuntimeStatus.RUNNING && runtime.getStartedAt() != null) {
            uptimeSeconds = Duration.between(runtime.getStartedAt(), Instant.now()).getSeconds();
        }

        return new RuntimeStatusResponse(
                runtime.getId(),
                runtime.getStatus(),
                "/mock/" + runtime.getId(),
                endpointsCount,
                storedEntities,
                collectionsCount,
                runtime.getStartedAt(),
                uptimeSeconds
        );
    }

    @Transactional
    public GenerateDataResponse generateMockData(UUID projectId, UUID runtimeId, GenerateDataRequest request, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        MockRuntime runtime = runtimeRepository.findByIdAndProjectId(runtimeId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Runtime not found with id: " + runtimeId));

        if (runtime.getStatus() != MockRuntimeStatus.RUNNING) {
            throw new IllegalStateException("Cannot generate mock data: Runtime is not in RUNNING status (current status: " + runtime.getStatus() + ")");
        }

        long effectiveSeed = request.seed() != null ? request.seed() : (System.currentTimeMillis() ^ (long) (Math.random() * 1000000L));
        int count = request.getEffectiveCount();
        String rawCollection = request.collection();
        String normalizedCollection = normalizeCollectionPath(rawCollection);

        NormalizedContract contract = runtime.getContractVersion().getNormalizedDefinition();
        NormalizedSchema itemSchema = resolveItemSchemaForCollection(contract, normalizedCollection);

        List<Map<String, Object>> entities = mockDataGenerator.generateCollection(itemSchema, count, effectiveSeed, contract);
        stateStore.initializeCollection(runtimeId, normalizedCollection, entities);

        return new GenerateDataResponse(
                runtimeId,
                normalizedCollection,
                entities.size(),
                effectiveSeed
        );
    }

    @Transactional
    public RuntimeResponse stopRuntime(UUID projectId, UUID runtimeId, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        MockRuntime runtime = runtimeRepository.findByIdAndProjectId(runtimeId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Runtime not found with id: " + runtimeId));

        runtime.setStatus(MockRuntimeStatus.STOPPED);
        runtime.setStoppedAt(Instant.now());
        MockRuntime saved = runtimeRepository.save(runtime);

        runtimeRegistry.unregister(runtimeId);

        return mapToResponse(saved);
    }

    @Transactional
    public void deleteRuntime(UUID projectId, UUID runtimeId, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        MockRuntime runtime = runtimeRepository.findByIdAndProjectId(runtimeId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Runtime not found with id: " + runtimeId));

        runtimeRegistry.unregister(runtimeId);
        stateStore.clearRuntime(runtimeId);
        runtimeRepository.delete(runtime);
    }

    private NormalizedSchema resolveItemSchemaForCollection(NormalizedContract contract, String collectionPath) {
        if (contract == null) {
            return NormalizedSchema.object(Collections.emptyMap(), Collections.emptyList(), "Generic Object");
        }

        // 1. Check matching endpoints (e.g. POST /pets requestBody or GET /pets response schema)
        if (contract.endpoints() != null) {
            for (NormalizedEndpoint ep : contract.endpoints()) {
                String epPath = normalizeCollectionPath(ep.path());
                if (epPath.equalsIgnoreCase(collectionPath)) {
                    if ("POST".equalsIgnoreCase(ep.method()) && ep.requestBody() != null && ep.requestBody().contentTypes() != null) {
                        for (var mt : ep.requestBody().contentTypes().values()) {
                            if (mt.schema() != null) {
                                return mt.schema();
                            }
                        }
                    }
                    if ("GET".equalsIgnoreCase(ep.method()) && ep.responses() != null) {
                        for (NormalizedResponse resp : ep.responses()) {
                            if (resp.statusCode() != null && resp.statusCode().startsWith("2") && resp.contentTypes() != null) {
                                for (var mt : resp.contentTypes().values()) {
                                    if (mt.schema() != null) {
                                        if ("array".equalsIgnoreCase(mt.schema().type()) && mt.schema().items() != null) {
                                            return mt.schema().items();
                                        }
                                        return mt.schema();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Check schemas map for matching entity name (e.g. /pets -> Pet or pets)
        String singular = collectionPath.replaceAll("^/", "");
        if (singular.endsWith("s") && singular.length() > 1) {
            singular = singular.substring(0, singular.length() - 1);
        }
        if (contract.schemas() != null) {
            for (Map.Entry<String, NormalizedSchema> entry : contract.schemas().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(singular) || entry.getKey().equalsIgnoreCase(collectionPath.replaceAll("^/", ""))) {
                    return entry.getValue();
                }
            }
        }

        return NormalizedSchema.object(Collections.emptyMap(), Collections.emptyList(), "Generic Entity");
    }

    private String normalizeCollectionPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private RuntimeResponse mapToResponse(MockRuntime runtime) {
        int endpointsCount = countEndpoints(runtime);
        int storedEntities = stateStore.getEntityCount(runtime.getId());

        return new RuntimeResponse(
                runtime.getId(),
                runtime.getProject().getId(),
                runtime.getContractVersion().getContract().getId(),
                runtime.getContractVersion().getId(),
                runtime.getContractVersion().getVersionNumber(),
                runtime.getName(),
                runtime.getStatus(),
                "/mock/" + runtime.getId(),
                endpointsCount,
                storedEntities,
                runtime.getCreatedAt(),
                runtime.getStartedAt(),
                runtime.getStoppedAt()
        );
    }

    private int countEndpoints(MockRuntime runtime) {
        if (runtime.getContractVersion() != null && runtime.getContractVersion().getNormalizedDefinition() != null) {
            NormalizedContract contract = runtime.getContractVersion().getNormalizedDefinition();
            return contract.endpoints() != null ? contract.endpoints().size() : 0;
        }
        return 0;
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
