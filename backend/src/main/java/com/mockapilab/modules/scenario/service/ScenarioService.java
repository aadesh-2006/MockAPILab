package com.mockapilab.modules.scenario.service;

import com.mockapilab.common.exception.ForbiddenException;
import com.mockapilab.common.exception.ResourceNotFoundException;
import com.mockapilab.modules.auth.security.UserPrincipal;
import com.mockapilab.modules.project.model.Project;
import com.mockapilab.modules.project.repository.ProjectRepository;
import com.mockapilab.modules.runtime.model.MockRuntime;
import com.mockapilab.modules.runtime.repository.MockRuntimeRepository;
import com.mockapilab.modules.scenario.dto.ScenarioRequest;
import com.mockapilab.modules.scenario.dto.ScenarioResponse;
import com.mockapilab.modules.scenario.model.Scenario;
import com.mockapilab.modules.scenario.model.ScenarioAction;
import com.mockapilab.modules.scenario.model.ScenarioStatus;
import com.mockapilab.modules.scenario.repository.ScenarioRepository;
import jakarta.validation.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service managing scenario CRUD operations, lifecycle state transitions, and authorization checks.
 */
@Service
public class ScenarioService {

    private final ScenarioRepository scenarioRepository;
    private final MockRuntimeRepository runtimeRepository;
    private final ProjectRepository projectRepository;

    public ScenarioService(
            ScenarioRepository scenarioRepository,
            MockRuntimeRepository runtimeRepository,
            ProjectRepository projectRepository
    ) {
        this.scenarioRepository = scenarioRepository;
        this.runtimeRepository = runtimeRepository;
        this.projectRepository = projectRepository;
    }

    @Transactional
    public ScenarioResponse createScenario(UUID projectId, UUID runtimeId, ScenarioRequest request, UserPrincipal principal) {
        Project project = verifyProjectOwnership(projectId, principal);
        MockRuntime runtime = verifyRuntimeInProject(runtimeId, projectId);

        validateScenarioParameters(request);

        Scenario scenario = new Scenario(
                runtime,
                project,
                request.name().trim(),
                request.description(),
                request.status() != null ? request.status() : ScenarioStatus.ACTIVE,
                request.pathPattern().trim(),
                request.httpMethod(),
                request.action(),
                request.statusCode(),
                request.delayMs(),
                request.probabilityPercent(),
                request.maxExecutions()
        );

        Scenario saved = scenarioRepository.save(scenario);
        return ScenarioResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<ScenarioResponse> getScenarios(UUID projectId, UUID runtimeId, UserPrincipal principal) {
        verifyProjectOwnership(projectId, principal);
        verifyRuntimeInProject(runtimeId, projectId);

        return scenarioRepository.findByRuntimeIdOrderByCreatedAtAsc(runtimeId)
                .stream()
                .map(ScenarioResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public ScenarioResponse getScenario(UUID projectId, UUID runtimeId, UUID scenarioId, UserPrincipal principal) {
        verifyProjectOwnership(projectId, principal);
        verifyRuntimeInProject(runtimeId, projectId);

        Scenario scenario = scenarioRepository.findByIdAndRuntimeId(scenarioId, runtimeId)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario not found with ID: " + scenarioId));

        return ScenarioResponse.fromEntity(scenario);
    }

    @Transactional
    public ScenarioResponse updateScenario(UUID projectId, UUID runtimeId, UUID scenarioId, ScenarioRequest request, UserPrincipal principal) {
        verifyProjectOwnership(projectId, principal);
        verifyRuntimeInProject(runtimeId, projectId);

        Scenario scenario = scenarioRepository.findByIdAndRuntimeId(scenarioId, runtimeId)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario not found with ID: " + scenarioId));

        validateScenarioParameters(request);

        scenario.setName(request.name().trim());
        scenario.setDescription(request.description());
        if (request.status() != null) {
            scenario.setStatus(request.status());
        }
        scenario.setPathPattern(request.pathPattern().trim());
        scenario.setHttpMethod(request.httpMethod());
        scenario.setAction(request.action());
        scenario.setStatusCode(request.statusCode());
        scenario.setDelayMs(request.delayMs());
        scenario.setProbabilityPercent(request.probabilityPercent());
        scenario.setMaxExecutions(request.maxExecutions());

        Scenario updated = scenarioRepository.save(scenario);
        return ScenarioResponse.fromEntity(updated);
    }

    @Transactional
    public void deleteScenario(UUID projectId, UUID runtimeId, UUID scenarioId, UserPrincipal principal) {
        verifyProjectOwnership(projectId, principal);
        verifyRuntimeInProject(runtimeId, projectId);

        Scenario scenario = scenarioRepository.findByIdAndRuntimeId(scenarioId, runtimeId)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario not found with ID: " + scenarioId));

        scenarioRepository.delete(scenario);
    }

    @Transactional
    public ScenarioResponse enableScenario(UUID projectId, UUID runtimeId, UUID scenarioId, UserPrincipal principal) {
        verifyProjectOwnership(projectId, principal);
        verifyRuntimeInProject(runtimeId, projectId);

        Scenario scenario = scenarioRepository.findByIdAndRuntimeId(scenarioId, runtimeId)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario not found with ID: " + scenarioId));

        scenario.setStatus(ScenarioStatus.ACTIVE);
        Scenario updated = scenarioRepository.save(scenario);
        return ScenarioResponse.fromEntity(updated);
    }

    @Transactional
    public ScenarioResponse disableScenario(UUID projectId, UUID runtimeId, UUID scenarioId, UserPrincipal principal) {
        verifyProjectOwnership(projectId, principal);
        verifyRuntimeInProject(runtimeId, projectId);

        Scenario scenario = scenarioRepository.findByIdAndRuntimeId(scenarioId, runtimeId)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario not found with ID: " + scenarioId));

        scenario.setStatus(ScenarioStatus.DISABLED);
        Scenario updated = scenarioRepository.save(scenario);
        return ScenarioResponse.fromEntity(updated);
    }

    private void validateScenarioParameters(ScenarioRequest request) {
        if (request.action() == null) {
            throw new ValidationException("Scenario action is required");
        }

        switch (request.action()) {
            case FORCE_STATUS -> {
                if (request.statusCode() == null) {
                    throw new ValidationException("statusCode is required for FORCE_STATUS action");
                }
                if (request.statusCode() < 100 || request.statusCode() > 599) {
                    throw new ValidationException("statusCode must be between 100 and 599");
                }
            }
            case DELAY -> {
                if (request.delayMs() == null) {
                    throw new ValidationException("delayMs is required for DELAY action");
                }
                if (request.delayMs() < 0 || request.delayMs() > 30000) {
                    throw new ValidationException("delayMs must be between 0 and 30000 ms (max 30 seconds)");
                }
            }
            case RANDOM_FAILURE -> {
                if (request.probabilityPercent() == null) {
                    throw new ValidationException("probabilityPercent is required for RANDOM_FAILURE action");
                }
                if (request.probabilityPercent() < 0 || request.probabilityPercent() > 100) {
                    throw new ValidationException("probabilityPercent must be between 0 and 100");
                }
                if (request.statusCode() == null) {
                    throw new ValidationException("statusCode is required for RANDOM_FAILURE action");
                }
                if (request.statusCode() < 100 || request.statusCode() > 599) {
                    throw new ValidationException("statusCode must be between 100 and 599");
                }
            }
        }
    }

    private Project verifyProjectOwnership(UUID projectId, UserPrincipal principal) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with ID: " + projectId));

        if (!project.getOwner().getId().equals(principal.getId())) {
            throw new ForbiddenException("Access denied: You do not own this project workspace.");
        }

        return project;
    }

    private MockRuntime runtimeInProject(UUID runtimeId, UUID projectId) {
        return verifyRuntimeInProject(runtimeId, projectId);
    }

    private MockRuntime verifyRuntimeInProject(UUID runtimeId, UUID projectId) {
        MockRuntime runtime = runtimeRepository.findById(runtimeId)
                .orElseThrow(() -> new ResourceNotFoundException("Mock runtime not found with ID: " + runtimeId));

        if (!runtime.getProject().getId().equals(projectId)) {
            throw new ForbiddenException("Access denied: Mock runtime does not belong to the specified project.");
        }

        return runtime;
    }
}
