package com.mockapilab.modules.runtime.generation.generators;

import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.runtime.generation.DataGenerationContext;
import org.springframework.stereotype.Component;

/**
 * Generator for deterministic boolean values.
 */
@Component
public class BooleanValueGenerator {

    public Boolean generate(NormalizedSchema schema, DataGenerationContext context) {
        String prop = context.getPropertyName() != null ? context.getPropertyName().toLowerCase() : "";
        if (prop.contains("active") || prop.contains("enabled") || prop.contains("verified") || prop.contains("success")) {
            return context.getSeed().nextDouble() < 0.8;
        }
        return context.getSeed().nextBoolean();
    }
}
