package com.mockapilab.modules.scenario.engine;

import com.mockapilab.modules.project.model.Project;
import com.mockapilab.modules.runtime.model.MockRuntime;
import com.mockapilab.modules.runtime.model.MockRuntimeStatus;
import com.mockapilab.modules.scenario.model.Scenario;
import com.mockapilab.modules.scenario.model.ScenarioAction;
import com.mockapilab.modules.scenario.model.ScenarioStatus;
import com.mockapilab.modules.scenario.repository.ScenarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScenarioEngineTest {

    @Mock
    private ScenarioRepository scenarioRepository;

    private ScenarioEngine scenarioEngine;
    private AtomicLong sleepDurationRecorded;

    private UUID runtimeId;
    private Project project;
    private MockRuntime runtime;

    @BeforeEach
    void setUp() {
        scenarioEngine = new ScenarioEngine(scenarioRepository);
        sleepDurationRecorded = new AtomicLong(-1);
        scenarioEngine.setSleeper(sleepDurationRecorded::set);

        runtimeId = UUID.randomUUID();
        project = new Project("Test Project", "Desc", null);
        project.setId(UUID.randomUUID());

        runtime = new MockRuntime(project, null, "Test Runtime", MockRuntimeStatus.RUNNING);
        runtime.setId(runtimeId);
    }

    @Test
    @DisplayName("Precedence: Exact path + exact method beats all other matching rules")
    void precedence_ExactPathExactMethod_Wins() {
        Scenario anyMethodScenario = createScenario(
                "Any Method Pattern",
                "/users/{id}",
                null,
                ScenarioAction.FORCE_STATUS,
                400,
                Instant.now().minusSeconds(100)
        );

        Scenario exactPathAnyMethod = createScenario(
                "Exact Path Any Method",
                "/users/123",
                null,
                ScenarioAction.FORCE_STATUS,
                401,
                Instant.now().minusSeconds(50)
        );

        Scenario exactPathExactMethod = createScenario(
                "Exact Path Exact Method",
                "/users/123",
                "GET",
                ScenarioAction.FORCE_STATUS,
                403,
                Instant.now().minusSeconds(10)
        );

        when(scenarioRepository.findByRuntimeIdAndStatusOrderByCreatedAtAsc(runtimeId, ScenarioStatus.ACTIVE))
                .thenReturn(List.of(anyMethodScenario, exactPathAnyMethod, exactPathExactMethod));

        when(scenarioRepository.incrementExecutionCountIfUnderLimit(eq(exactPathExactMethod.getId()), any(Instant.class)))
                .thenReturn(1);

        ScenarioEvaluationResult result = scenarioEngine.evaluateAndExecute(runtimeId, "GET", "/users/123");

        assertThat(result.shouldShortCircuit()).isTrue();
        assertThat(result.injectedResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(scenarioRepository).incrementExecutionCountIfUnderLimit(eq(exactPathExactMethod.getId()), any(Instant.class));
        verify(scenarioRepository, never()).incrementExecutionCountIfUnderLimit(eq(exactPathAnyMethod.getId()), any(Instant.class));
    }

    @Test
    @DisplayName("Precedence: Exact path + any method beats pattern path + exact method")
    void precedence_ExactPathAnyMethod_Beats_PatternExactMethod() {
        Scenario patternExactMethod = createScenario(
                "Pattern Exact Method",
                "/users/{id}",
                "GET",
                ScenarioAction.FORCE_STATUS,
                500,
                Instant.now().minusSeconds(100)
        );

        Scenario exactPathAnyMethod = createScenario(
                "Exact Path Any Method",
                "/users/123",
                null,
                ScenarioAction.FORCE_STATUS,
                429,
                Instant.now().minusSeconds(50)
        );

        when(scenarioRepository.findByRuntimeIdAndStatusOrderByCreatedAtAsc(runtimeId, ScenarioStatus.ACTIVE))
                .thenReturn(List.of(patternExactMethod, exactPathAnyMethod));

        when(scenarioRepository.incrementExecutionCountIfUnderLimit(eq(exactPathAnyMethod.getId()), any(Instant.class)))
                .thenReturn(1);

        ScenarioEvaluationResult result = scenarioEngine.evaluateAndExecute(runtimeId, "GET", "/users/123");

        assertThat(result.shouldShortCircuit()).isTrue();
        assertThat(result.injectedResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("Pattern matching supports OpenAPI template /users/{id} and wildcards")
    void patternMatching_TemplateAndWildcards_Success() {
        Scenario templateScenario = createScenario("Template", "/users/{id}", "DELETE", ScenarioAction.FORCE_STATUS, 404, Instant.now());
        Scenario wildcardScenario = createScenario("Wildcard", "/orders/**", "GET", ScenarioAction.FORCE_STATUS, 503, Instant.now());

        when(scenarioRepository.findByRuntimeIdAndStatusOrderByCreatedAtAsc(runtimeId, ScenarioStatus.ACTIVE))
                .thenReturn(List.of(templateScenario, wildcardScenario));

        when(scenarioRepository.incrementExecutionCountIfUnderLimit(eq(templateScenario.getId()), any(Instant.class)))
                .thenReturn(1);
        when(scenarioRepository.incrementExecutionCountIfUnderLimit(eq(wildcardScenario.getId()), any(Instant.class)))
                .thenReturn(1);

        // 1. Matches template
        ScenarioEvaluationResult res1 = scenarioEngine.evaluateAndExecute(runtimeId, "DELETE", "/users/abc-123");
        assertThat(res1.shouldShortCircuit()).isTrue();
        assertThat(res1.injectedResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 2. Matches deep wildcard
        ScenarioEvaluationResult res2 = scenarioEngine.evaluateAndExecute(runtimeId, "GET", "/orders/2026/items/456");
        assertThat(res2.shouldShortCircuit()).isTrue();
        assertThat(res2.injectedResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("FORCE_STATUS generates structured JSON error response")
    @SuppressWarnings("unchecked")
    void forceStatus_BuildsStructuredErrorJson() {
        Scenario scenario = createScenario("Unauthorized Rule", "/secure", "GET", ScenarioAction.FORCE_STATUS, 401, Instant.now());

        when(scenarioRepository.findByRuntimeIdAndStatusOrderByCreatedAtAsc(runtimeId, ScenarioStatus.ACTIVE))
                .thenReturn(List.of(scenario));
        when(scenarioRepository.incrementExecutionCountIfUnderLimit(eq(scenario.getId()), any(Instant.class)))
                .thenReturn(1);

        ScenarioEvaluationResult result = scenarioEngine.evaluateAndExecute(runtimeId, "GET", "/secure");

        assertThat(result.shouldShortCircuit()).isTrue();
        assertThat(result.injectedResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Map<String, Object> body = (Map<String, Object>) result.injectedResponse().getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("error")).isEqualTo("SCENARIO_INJECTED_FAILURE");
        assertThat(body.get("message")).asString().contains("Unauthorized Rule");
        assertThat(body.get("scenarioId")).isEqualTo(scenario.getId().toString());
        assertThat(body.get("status")).isEqualTo(401);
    }

    @Test
    @DisplayName("DELAY action sleeps for bounded duration and allows normal execution to proceed")
    void delayAction_SleepsAndPassesThrough() {
        Scenario scenario = createScenario("Latency", "/users", "GET", ScenarioAction.DELAY, null, Instant.now());
        scenario.setDelayMs(1500);

        when(scenarioRepository.findByRuntimeIdAndStatusOrderByCreatedAtAsc(runtimeId, ScenarioStatus.ACTIVE))
                .thenReturn(List.of(scenario));
        when(scenarioRepository.incrementExecutionCountIfUnderLimit(eq(scenario.getId()), any(Instant.class)))
                .thenReturn(1);

        ScenarioEvaluationResult result = scenarioEngine.evaluateAndExecute(runtimeId, "GET", "/users");

        assertThat(result.shouldShortCircuit()).isFalse();
        assertThat(result.delayAppliedMs()).isEqualTo(1500);
        assertThat(sleepDurationRecorded.get()).isEqualTo(1500);
    }

    @Test
    @DisplayName("RANDOM_FAILURE at 0% never triggers and does not increment execution count")
    void randomFailure_ZeroPercent_NeverFails() {
        Scenario scenario = createScenario("Flaky 0%", "/users", "GET", ScenarioAction.RANDOM_FAILURE, 500, Instant.now());
        scenario.setProbabilityPercent(0);

        when(scenarioRepository.findByRuntimeIdAndStatusOrderByCreatedAtAsc(runtimeId, ScenarioStatus.ACTIVE))
                .thenReturn(List.of(scenario));

        ScenarioEvaluationResult result = scenarioEngine.evaluateAndExecute(runtimeId, "GET", "/users");

        assertThat(result.shouldShortCircuit()).isFalse();
        verify(scenarioRepository, never()).incrementExecutionCountIfUnderLimit(any(), any());
    }

    @Test
    @DisplayName("RANDOM_FAILURE at 100% always triggers and increments execution count")
    void randomFailure_HundredPercent_AlwaysFails() {
        Scenario scenario = createScenario("Flaky 100%", "/users", "GET", ScenarioAction.RANDOM_FAILURE, 502, Instant.now());
        scenario.setProbabilityPercent(100);

        when(scenarioRepository.findByRuntimeIdAndStatusOrderByCreatedAtAsc(runtimeId, ScenarioStatus.ACTIVE))
                .thenReturn(List.of(scenario));
        when(scenarioRepository.incrementExecutionCountIfUnderLimit(eq(scenario.getId()), any(Instant.class)))
                .thenReturn(1);

        ScenarioEvaluationResult result = scenarioEngine.evaluateAndExecute(runtimeId, "GET", "/users");

        assertThat(result.shouldShortCircuit()).isTrue();
        assertThat(result.injectedResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        verify(scenarioRepository).incrementExecutionCountIfUnderLimit(eq(scenario.getId()), any(Instant.class));
    }

    @Test
    @DisplayName("maxExecutions limit reached returns pass when count cannot be reserved")
    void maxExecutions_LimitReached_PassesThrough() {
        Scenario scenario = createScenario("Limited Rule", "/users", "GET", ScenarioAction.FORCE_STATUS, 500, Instant.now());
        scenario.setMaxExecutions(5);
        scenario.setExecutionCount(5);

        when(scenarioRepository.findByRuntimeIdAndStatusOrderByCreatedAtAsc(runtimeId, ScenarioStatus.ACTIVE))
                .thenReturn(List.of(scenario));
        // Atomic reservation returns 0 (limit exceeded)
        when(scenarioRepository.incrementExecutionCountIfUnderLimit(eq(scenario.getId()), any(Instant.class)))
                .thenReturn(0);

        ScenarioEvaluationResult result = scenarioEngine.evaluateAndExecute(runtimeId, "GET", "/users");

        assertThat(result.shouldShortCircuit()).isFalse();
    }

    private Scenario createScenario(String name, String path, String method, ScenarioAction action, Integer statusCode, Instant createdAt) {
        Scenario scenario = new Scenario(runtime, project, name, "Description", ScenarioStatus.ACTIVE, path, method, action, statusCode, null, null, null);
        scenario.setId(UUID.randomUUID());
        scenario.setCreatedAt(createdAt);
        return scenario;
    }
}
