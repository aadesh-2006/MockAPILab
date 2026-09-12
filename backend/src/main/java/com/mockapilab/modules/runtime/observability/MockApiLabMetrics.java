package com.mockapilab.modules.runtime.observability;

import com.mockapilab.modules.runtime.model.GenerationJobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Centralized platform metrics recorder integrating with Micrometer / Spring Actuator.
 * Exposes lightweight counters and timers for mock requests, scenarios, generation jobs, and drift analysis.
 */
@Component
public class MockApiLabMetrics {

    private final MeterRegistry meterRegistry;

    public MockApiLabMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordMockRequest(UUID runtimeId, String method, int statusCode, long durationMs) {
        String statusGroup = (statusCode / 100) + "xx";
        Counter.builder("mockapilab.mock.requests.total")
                .description("Total number of dynamic mock API requests handled")
                .tag("runtimeId", runtimeId != null ? runtimeId.toString() : "unknown")
                .tag("method", method != null ? method : "UNKNOWN")
                .tag("statusGroup", statusGroup)
                .tag("statusCode", String.valueOf(statusCode))
                .register(meterRegistry)
                .increment();

        Timer.builder("mockapilab.mock.requests.duration")
                .description("Execution latency of mock API requests")
                .tag("method", method != null ? method : "UNKNOWN")
                .tag("statusGroup", statusGroup)
                .register(meterRegistry)
                .record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    public void recordScenarioTriggered(UUID runtimeId, String action, String path) {
        Counter.builder("mockapilab.scenario.triggered.total")
                .description("Total number of interactive failure scenarios triggered")
                .tag("runtimeId", runtimeId != null ? runtimeId.toString() : "unknown")
                .tag("action", action != null ? action : "UNKNOWN")
                .register(meterRegistry)
                .increment();
    }

    public void recordGenerationJob(GenerationJobStatus status) {
        Counter.builder("mockapilab.generation.jobs.total")
                .description("Total number of async data generation jobs by lifecycle status")
                .tag("status", status != null ? status.name() : "UNKNOWN")
                .register(meterRegistry)
                .increment();
    }

    public void recordDriftAnalysis(int totalChanges, int breakingCount, int nonBreakingCount, int informationalCount) {
        Counter.builder("mockapilab.drift.analyses.total")
                .description("Total number of contract drift analyses performed")
                .register(meterRegistry)
                .increment();

        if (breakingCount > 0) {
            Counter.builder("mockapilab.drift.changes.total")
                    .tag("classification", "BREAKING")
                    .register(meterRegistry)
                    .increment(breakingCount);
        }
        if (nonBreakingCount > 0) {
            Counter.builder("mockapilab.drift.changes.total")
                    .tag("classification", "NON_BREAKING")
                    .register(meterRegistry)
                    .increment(nonBreakingCount);
        }
        if (informationalCount > 0) {
            Counter.builder("mockapilab.drift.changes.total")
                    .tag("classification", "INFORMATIONAL")
                    .register(meterRegistry)
                    .increment(informationalCount);
        }
    }
}