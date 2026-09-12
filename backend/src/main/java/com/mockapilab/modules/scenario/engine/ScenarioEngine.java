package com.mockapilab.modules.scenario.engine;

import com.mockapilab.common.logging.CorrelationIdFilter;
import com.mockapilab.modules.runtime.observability.MockApiLabMetrics;
import com.mockapilab.modules.scenario.model.Scenario;
import com.mockapilab.modules.scenario.model.ScenarioAction;
import com.mockapilab.modules.scenario.model.ScenarioStatus;
import com.mockapilab.modules.scenario.repository.ScenarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.AntPathMatcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Core engine evaluating active scenarios and executing dynamic failure injection and latency policies.
 */
@Component
public class ScenarioEngine {

    private static final Logger log = LoggerFactory.getLogger(ScenarioEngine.class);
    public static final int MAX_DELAY_MS = 30000;

    private final ScenarioRepository scenarioRepository;
    private final MockApiLabMetrics metrics;
    private Sleeper sleeper = Thread::sleep;

    public ScenarioEngine(ScenarioRepository scenarioRepository) {
        this(scenarioRepository, null);
    }

    @Autowired
    public ScenarioEngine(ScenarioRepository scenarioRepository, @Autowired(required = false) MockApiLabMetrics metrics) {
        this.scenarioRepository = scenarioRepository;
        this.metrics = metrics;
    }

    /**
     * Functional interface for delaying thread execution, allowing fast mocking in tests.
     */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public void setSleeper(Sleeper sleeper) {
        this.sleeper = sleeper != null ? sleeper : Thread::sleep;
    }

    /**
     * Evaluates active scenarios for a runtime against the given request.
     *
     * @param runtimeId   the mock runtime ID
     * @param httpMethod  the incoming request HTTP method (e.g. GET, POST)
     * @param requestPath the normalized sub-path (e.g. /users, /users/123)
     * @return evaluation result indicating short-circuit response, delay, or normal pass
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ScenarioEvaluationResult evaluateAndExecute(UUID runtimeId, String httpMethod, String requestPath) {
        List<Scenario> activeScenarios = scenarioRepository.findByRuntimeIdAndStatusOrderByCreatedAtAsc(runtimeId, ScenarioStatus.ACTIVE);
        if (activeScenarios.isEmpty()) {
            return ScenarioEvaluationResult.pass();
        }

        String normalizedPath = normalizePath(requestPath);
        String upperMethod = httpMethod != null ? httpMethod.trim().toUpperCase() : "GET";

        // Score and filter candidates
        List<CandidateMatch> candidates = new ArrayList<>();
        for (Scenario scenario : activeScenarios) {
            int score = calculateMatchScore(scenario, upperMethod, normalizedPath);
            if (score > 0) {
                candidates.add(new CandidateMatch(scenario, score));
            }
        }

        if (candidates.isEmpty()) {
            return ScenarioEvaluationResult.pass();
        }

        // Sort by precedence: score descending, then createdAt ascending, then ID ascending
        candidates.sort(Comparator
                .comparingInt(CandidateMatch::score).reversed()
                .thenComparing((CandidateMatch c) -> c.scenario().getCreatedAt())
                .thenComparing((CandidateMatch c) -> c.scenario().getId().toString())
        );

        // Attempt execution on best matching candidate
        for (CandidateMatch candidate : candidates) {
            Scenario scenario = candidate.scenario();
            ScenarioAction action = scenario.getAction();

            switch (action) {
                case FORCE_STATUS -> {
                    boolean reserved = reserveExecution(scenario.getId());
                    if (!reserved) {
                        continue; // max executions exceeded concurrently, try next candidate
                    }

                    int statusCode = scenario.getStatusCode() != null ? scenario.getStatusCode() : 500;
                    log.info("scenario_triggered runtimeId={} scenarioId={} action=FORCE_STATUS path={} method={} statusCode={}",
                            runtimeId, scenario.getId(), normalizedPath, upperMethod, statusCode);

                    if (metrics != null) {
                        metrics.recordScenarioTriggered(runtimeId, "FORCE_STATUS", normalizedPath);
                    }

                    return ScenarioEvaluationResult.shortCircuit(buildInjectedResponse(scenario, statusCode));
                }

                case DELAY -> {
                    boolean reserved = reserveExecution(scenario.getId());
                    if (!reserved) {
                        continue;
                    }

                    int rawDelay = scenario.getDelayMs() != null ? scenario.getDelayMs() : 1000;
                    int boundedDelay = Math.max(0, Math.min(rawDelay, MAX_DELAY_MS));

                    log.info("scenario_triggered runtimeId={} scenarioId={} action=DELAY path={} method={} delayMs={}",
                            runtimeId, scenario.getId(), normalizedPath, upperMethod, boundedDelay);

                    if (metrics != null) {
                        metrics.recordScenarioTriggered(runtimeId, "DELAY", normalizedPath);
                    }

                    try {
                        sleeper.sleep(boundedDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Scenario delay interrupted for runtimeId={} scenarioId={}", runtimeId, scenario.getId());
                    }

                    return ScenarioEvaluationResult.delayed(boundedDelay);
                }

                case RANDOM_FAILURE -> {
                    int probability = scenario.getProbabilityPercent() != null ? scenario.getProbabilityPercent() : 0;
                    if (probability <= 0) {
                        return ScenarioEvaluationResult.pass();
                    }

                    boolean shouldFail = probability >= 100 || ThreadLocalRandom.current().nextInt(100) < probability;
                    if (shouldFail) {
                        boolean reserved = reserveExecution(scenario.getId());
                        if (!reserved) {
                            continue;
                        }

                        int statusCode = scenario.getStatusCode() != null ? scenario.getStatusCode() : 500;
                        log.info("scenario_triggered runtimeId={} scenarioId={} action=RANDOM_FAILURE path={} method={} statusCode={} probability={}%",
                                runtimeId, scenario.getId(), normalizedPath, upperMethod, statusCode, probability);

                        if (metrics != null) {
                            metrics.recordScenarioTriggered(runtimeId, "RANDOM_FAILURE", normalizedPath);
                        }

                        return ScenarioEvaluationResult.shortCircuit(buildInjectedResponse(scenario, statusCode));
                    }

                    return ScenarioEvaluationResult.pass();
                }
            }
        }

        return ScenarioEvaluationResult.pass();
    }

    private boolean reserveExecution(UUID scenarioId) {
        int updatedRows = scenarioRepository.incrementExecutionCountIfUnderLimit(scenarioId, Instant.now());
        return updatedRows > 0;
    }

    private ResponseEntity<Object> buildInjectedResponse(Scenario scenario, int statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", statusCode);
        body.put("error", "SCENARIO_INJECTED_FAILURE");
        body.put("message", "Response generated by active mock scenario: " + scenario.getName());
        body.put("scenarioId", scenario.getId().toString());
        body.put("path", scenario.getPathPattern());
        body.put("requestId", CorrelationIdFilter.getCorrelationId());

        return ResponseEntity.status(statusCode).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /**
     * Calculates match score according to specified precedence rules:
     * 1. Exact path + exact method -> 4
     * 2. Exact path + any method   -> 3
     * 3. Pattern path + exact method -> 2
     * 4. Pattern path + any method   -> 1
     * 0 -> No match
     */
    public int calculateMatchScore(Scenario scenario, String requestMethod, String requestPath) {
        String pattern = normalizePath(scenario.getPathPattern());
        boolean isExactPath = isExactPattern(pattern);
        boolean pathMatches = isExactPath ? pattern.equalsIgnoreCase(requestPath) : matchesPattern(pattern, requestPath);

        if (!pathMatches) {
            return 0;
        }

        boolean isExactMethod = scenario.getHttpMethod() != null
                && !scenario.getHttpMethod().isBlank()
                && !scenario.getHttpMethod().equalsIgnoreCase("ALL")
                && !scenario.getHttpMethod().equalsIgnoreCase("*");

        if (isExactMethod) {
            if (!scenario.getHttpMethod().equalsIgnoreCase(requestMethod)) {
                return 0;
            }
            return isExactPath ? 4 : 2;
        } else {
            return isExactPath ? 3 : 1;
        }
    }

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private boolean isExactPattern(String pattern) {
        return !pattern.contains("{") && !pattern.contains("*") && !pattern.contains("?");
    }

    private boolean matchesPattern(String pattern, String requestPath) {
        return PATH_MATCHER.match(pattern, requestPath);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private record CandidateMatch(Scenario scenario, int score) {
    }
}