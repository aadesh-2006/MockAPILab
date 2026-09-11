package com.mockapilab.modules.runtime.engine;

import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.runtime.generation.DataGenerationContext;
import com.mockapilab.modules.runtime.generation.GenerationSeed;
import com.mockapilab.modules.runtime.generation.MockDataGenerator;
import org.springframework.stereotype.Component;

/**
 * Deterministic schema-driven response generator for OpenAPI mock responses.
 * <p>
 * Delegates to {@link MockDataGenerator} for realistic, constraint-aware generation.
 */
@Component
public class DeterministicResponseGenerator {

    private final MockDataGenerator mockDataGenerator;

    public DeterministicResponseGenerator(MockDataGenerator mockDataGenerator) {
        this.mockDataGenerator = mockDataGenerator;
    }

    public Object generateResponsePayload(NormalizedEndpoint endpoint, String targetStatusCode, NormalizedContract contract) {
        long seed = (endpoint != null && endpoint.path() != null)
                ? (endpoint.path().hashCode() * 31L + (endpoint.method() != null ? endpoint.method().hashCode() : 0))
                : 42L;
        return mockDataGenerator.generateResponsePayload(endpoint, targetStatusCode, contract, seed);
    }

    public Object generateFromSchema(NormalizedSchema schema, String propertyName, NormalizedContract contract, int depth) {
        GenerationSeed seed = GenerationSeed.from(propertyName != null ? propertyName.hashCode() : 42L);
        DataGenerationContext context = new DataGenerationContext(
                seed,
                contract,
                depth,
                5,
                2,
                propertyName,
                "",
                null
        );
        return mockDataGenerator.generate(schema, context);
    }
}
