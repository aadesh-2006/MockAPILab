package com.mockapilab.modules.scenario.engine;

import org.springframework.http.ResponseEntity;

/**
 * Result of evaluating active scenarios against an incoming mock request.
 */
public record ScenarioEvaluationResult(
        boolean shouldShortCircuit,
        ResponseEntity<Object> injectedResponse,
        long delayAppliedMs
) {
    public static ScenarioEvaluationResult pass() {
        return new ScenarioEvaluationResult(false, null, 0);
    }

    public static ScenarioEvaluationResult delayed(long delayMs) {
        return new ScenarioEvaluationResult(false, null, delayMs);
    }

    public static ScenarioEvaluationResult shortCircuit(ResponseEntity<Object> response) {
        return new ScenarioEvaluationResult(true, response, 0);
    }
}
