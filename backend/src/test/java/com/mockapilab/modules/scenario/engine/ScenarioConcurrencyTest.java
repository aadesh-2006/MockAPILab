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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScenarioConcurrencyTest {

    @Mock
    private ScenarioRepository scenarioRepository;

    private ScenarioEngine scenarioEngine;
    private UUID runtimeId;
    private Scenario scenario;

    @BeforeEach
    void setUp() {
        scenarioEngine = new ScenarioEngine(scenarioRepository);
        runtimeId = UUID.randomUUID();

        Project project = new Project("Concurrency Project", "Desc", null);
        project.setId(UUID.randomUUID());

        MockRuntime runtime = new MockRuntime(project, null, "Runtime", MockRuntimeStatus.RUNNING);
        runtime.setId(runtimeId);

        scenario = new Scenario(runtime, project, "Max 5 Limit", "Desc", ScenarioStatus.ACTIVE, "/users", "GET", ScenarioAction.FORCE_STATUS, 429, null, null, 5);
        scenario.setId(UUID.randomUUID());
        scenario.setCreatedAt(Instant.now());
    }

    @Test
    @DisplayName("Concurrent requests respecting maxExecutions limit: exactly 5 succeed, remainder pass through")
    void concurrentRequests_RespectMaxExecutions() throws InterruptedException {
        when(scenarioRepository.findByRuntimeIdAndStatusOrderByCreatedAtAsc(runtimeId, ScenarioStatus.ACTIVE))
                .thenReturn(List.of(scenario));

        AtomicInteger currentExecutionCount = new AtomicInteger(0);
        int maxExecutions = 5;

        // Simulate atomic DB CAS update
        when(scenarioRepository.incrementExecutionCountIfUnderLimit(eq(scenario.getId()), any(Instant.class)))
                .thenAnswer(inv -> {
                    int prev = currentExecutionCount.getAndUpdate(c -> c < maxExecutions ? c + 1 : c);
                    return prev < maxExecutions ? 1 : 0;
                });

        int totalThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(totalThreads);

        AtomicInteger shortCircuitCount = new AtomicInteger(0);
        AtomicInteger passCount = new AtomicInteger(0);

        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ScenarioEvaluationResult result = scenarioEngine.evaluateAndExecute(runtimeId, "GET", "/users");
                    if (result.shouldShortCircuit()) {
                        shortCircuitCount.incrementAndGet();
                    } else {
                        passCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await();
        executor.shutdown();

        assertThat(shortCircuitCount.get()).isEqualTo(5);
        assertThat(passCount.get()).isEqualTo(15);
        assertThat(currentExecutionCount.get()).isEqualTo(5);
    }
}
