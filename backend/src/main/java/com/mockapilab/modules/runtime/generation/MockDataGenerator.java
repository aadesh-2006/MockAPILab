package com.mockapilab.modules.runtime.generation;

import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;

import java.util.List;
import java.util.Map;

/**
 * Interface defining the MockAPILab data generation engine.
 */
public interface MockDataGenerator {

    /**
     * Generates a data value from a NormalizedSchema within a generation context.
     */
    Object generate(NormalizedSchema schema, DataGenerationContext context);

    /**
     * Generates a realistic mock response payload for an endpoint and status code using a deterministic seed.
     */
    Object generateResponsePayload(NormalizedEndpoint endpoint, String targetStatusCode, NormalizedContract contract, long seed);

    /**
     * Generates a list of mock entities according to an item schema using a deterministic seed.
     */
    List<Map<String, Object>> generateCollection(NormalizedSchema itemSchema, int count, long seed, NormalizedContract contract);
}
