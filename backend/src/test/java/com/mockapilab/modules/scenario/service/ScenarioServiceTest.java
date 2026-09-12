package com.mockapilab.modules.scenario.service;

import com.mockapilab.common.exception.ForbiddenException;
import com.mockapilab.common.exception.ResourceNotFoundException;
import com.mockapilab.modules.auth.model.User;
import com.mockapilab.modules.auth.security.UserPrincipal;
import com.mockapilab.modules.project.model.Project;
import com.mockapilab.modules.project.repository.ProjectRepository;
import com.mockapilab.modules.runtime.model.MockRuntime;
import com.mockapilab.modules.runtime.model.MockRuntimeStatus;
import com.mockapilab.modules.runtime.repository.MockRuntimeRepository;
import com.mockapilab.modules.scenario.dto.ScenarioRequest;
import com.mockapilab.modules.scenario.dto.ScenarioResponse;
import com.mockapilab.modules.scenario.model.Scenario;
import com.mockapilab.modules.scenario.model.ScenarioAction;
import com.mockapilab.modules.scenario.model.ScenarioStatus;
import com.mockapilab.modules.scenario.repository.ScenarioRepository;
import jakarta.validation.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScenarioServiceTest {

    @Mock
    private ScenarioRepository scenarioRepository;

    @Mock
    private MockRuntimeRepository runtimeRepository;

    @Mock
    private ProjectRepository projectRepository;

    private ScenarioService scenarioService;

    private User owner;
    private UserPrincipal ownerPrincipal;
    private User otherUser;
    private UserPrincipal otherPrincipal;
    private Project project;
    private MockRuntime runtime;

    @BeforeEach
    void setUp() {
        scenarioService = new ScenarioService(scenarioRepository, runtimeRepository, projectRepository);

        owner = new User("owner@example.com", "hash", "Owner");
        owner.setId(UUID.randomUUID());
        ownerPrincipal = UserPrincipal.fromEntity(owner);

        otherUser = new User("other@example.com", "hash", "Other");
        otherUser.setId(UUID.randomUUID());
        otherPrincipal = UserPrincipal.fromEntity(otherUser);

        project = new Project("Test Project", "Desc", owner);
        project.setId(UUID.randomUUID());

        runtime = new MockRuntime(project, null, "Test Runtime", MockRuntimeStatus.RUNNING);
        runtime.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("Successfully create a FORCE_STATUS scenario")
    void createScenario_ForceStatus_Success() {
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        when(runtimeRepository.findById(runtime.getId())).thenReturn(Optional.of(runtime));
        when(scenarioRepository.save(any(Scenario.class))).thenAnswer(inv -> {
            Scenario s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        ScenarioRequest request = new ScenarioRequest(
                "Auth Failure",
                "Always return 401 for users",
                ScenarioStatus.ACTIVE,
                "/users",
                "GET",
                ScenarioAction.FORCE_STATUS,
                401,
                null,
                null,
                null
        );

        ScenarioResponse response = scenarioService.createScenario(project.getId(), runtime.getId(), request, ownerPrincipal);

        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo("Auth Failure");
        assertThat(response.action()).isEqualTo(ScenarioAction.FORCE_STATUS);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.status()).isEqualTo(ScenarioStatus.ACTIVE);
    }

    @Test
    @DisplayName("Validation fails when FORCE_STATUS has null statusCode")
    void createScenario_ForceStatus_MissingStatusCode_Throws() {
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        when(runtimeRepository.findById(runtime.getId())).thenReturn(Optional.of(runtime));

        ScenarioRequest request = new ScenarioRequest(
                "Invalid",
                "No status code",
                ScenarioStatus.ACTIVE,
                "/users",
                "GET",
                ScenarioAction.FORCE_STATUS,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> scenarioService.createScenario(project.getId(), runtime.getId(), request, ownerPrincipal))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("statusCode is required");
    }

    @Test
    @DisplayName("Validation fails when DELAY exceeds 30000ms")
    void createScenario_Delay_TooHigh_Throws() {
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        when(runtimeRepository.findById(runtime.getId())).thenReturn(Optional.of(runtime));

        ScenarioRequest request = new ScenarioRequest(
                "Extreme Delay",
                "Delay 40s",
                ScenarioStatus.ACTIVE,
                "/users",
                "GET",
                ScenarioAction.DELAY,
                null,
                40000,
                null,
                null
        );

        assertThatThrownBy(() -> scenarioService.createScenario(project.getId(), runtime.getId(), request, ownerPrincipal))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("30000 ms");
    }

    @Test
    @DisplayName("Validation fails when RANDOM_FAILURE has missing probability")
    void createScenario_RandomFailure_MissingProbability_Throws() {
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        when(runtimeRepository.findById(runtime.getId())).thenReturn(Optional.of(runtime));

        ScenarioRequest request = new ScenarioRequest(
                "Random Failure",
                "Flaky server",
                ScenarioStatus.ACTIVE,
                "/users",
                "GET",
                ScenarioAction.RANDOM_FAILURE,
                500,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> scenarioService.createScenario(project.getId(), runtime.getId(), request, ownerPrincipal))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("probabilityPercent is required");
    }

    @Test
    @DisplayName("Ownership isolation prevents non-owner from accessing scenarios")
    void getScenarios_UnauthorizedUser_ThrowsForbidden() {
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> scenarioService.getScenarios(project.getId(), runtime.getId(), otherPrincipal))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("Enable and disable toggle scenario status correctly")
    void enableDisableScenario_Success() {
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        when(runtimeRepository.findById(runtime.getId())).thenReturn(Optional.of(runtime));

        Scenario scenario = new Scenario(runtime, project, "Test", "Desc", ScenarioStatus.DISABLED, "/users", "GET", ScenarioAction.FORCE_STATUS, 500, null, null, null);
        scenario.setId(UUID.randomUUID());

        when(scenarioRepository.findByIdAndRuntimeId(scenario.getId(), runtime.getId())).thenReturn(Optional.of(scenario));
        when(scenarioRepository.save(any(Scenario.class))).thenAnswer(inv -> inv.getArgument(0));

        ScenarioResponse enabled = scenarioService.enableScenario(project.getId(), runtime.getId(), scenario.getId(), ownerPrincipal);
        assertThat(enabled.status()).isEqualTo(ScenarioStatus.ACTIVE);

        ScenarioResponse disabled = scenarioService.disableScenario(project.getId(), runtime.getId(), scenario.getId(), ownerPrincipal);
        assertThat(disabled.status()).isEqualTo(ScenarioStatus.DISABLED);
    }

    @Test
    @DisplayName("Delete scenario removes entity from database")
    void deleteScenario_Success() {
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        when(runtimeRepository.findById(runtime.getId())).thenReturn(Optional.of(runtime));

        Scenario scenario = new Scenario(runtime, project, "To Delete", "Desc", ScenarioStatus.ACTIVE, "/users", "GET", ScenarioAction.FORCE_STATUS, 500, null, null, null);
        scenario.setId(UUID.randomUUID());

        when(scenarioRepository.findByIdAndRuntimeId(scenario.getId(), runtime.getId())).thenReturn(Optional.of(scenario));

        scenarioService.deleteScenario(project.getId(), runtime.getId(), scenario.getId(), ownerPrincipal);

        verify(scenarioRepository).delete(scenario);
    }
}
