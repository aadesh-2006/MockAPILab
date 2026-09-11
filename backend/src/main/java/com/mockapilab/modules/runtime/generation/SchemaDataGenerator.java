package com.mockapilab.modules.runtime.generation;

import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.runtime.generation.generators.BooleanValueGenerator;
import com.mockapilab.modules.runtime.generation.generators.CollectionValueGenerator;
import com.mockapilab.modules.runtime.generation.generators.DateTimeValueGenerator;
import com.mockapilab.modules.runtime.generation.generators.NumberValueGenerator;
import com.mockapilab.modules.runtime.generation.generators.StringValueGenerator;
import org.springframework.stereotype.Component;

/**
 * Core schema evaluator coordinating prioritized and constraint-aware value generation.
 */
@Component
public class SchemaDataGenerator {

    private final StringValueGenerator stringValueGenerator;
    private final NumberValueGenerator numberValueGenerator;
    private final BooleanValueGenerator booleanValueGenerator;
    private final DateTimeValueGenerator dateTimeValueGenerator;
    private final CollectionValueGenerator collectionValueGenerator;

    public SchemaDataGenerator(
            StringValueGenerator stringValueGenerator,
            NumberValueGenerator numberValueGenerator,
            BooleanValueGenerator booleanValueGenerator,
            DateTimeValueGenerator dateTimeValueGenerator,
            CollectionValueGenerator collectionValueGenerator
    ) {
        this.stringValueGenerator = stringValueGenerator;
        this.numberValueGenerator = numberValueGenerator;
        this.booleanValueGenerator = booleanValueGenerator;
        this.dateTimeValueGenerator = dateTimeValueGenerator;
        this.collectionValueGenerator = collectionValueGenerator;
        if (collectionValueGenerator != null) {
            collectionValueGenerator.setSchemaDataGenerator(this);
        }
    }

    public Object generate(NormalizedSchema schema, DataGenerationContext context) {
        if (schema == null || context.isDepthExceeded()) {
            return null;
        }

        // 1. Resolve $ref if present
        if (schema.ref() != null) {
            String refName = extractRefName(schema.ref());
            if (context.isRefActive(refName)) {
                return null; // Cycle guard
            }

            NormalizedSchema resolved = resolveRef(refName, context.getContract());
            if (resolved != null) {
                return generate(resolved, context.withRef(refName));
            }
        }

        // 2. Priority 1: Explicit Example
        if (schema.example() != null) {
            return schema.example();
        }

        // 3. Priority 2: Default Value
        if (schema.defaultValue() != null) {
            return schema.defaultValue();
        }

        // 4. Priority 3: Enum Constants (deterministic selection using seed)
        if (schema.enumConstants() != null && !schema.enumConstants().isEmpty()) {
            return context.getSeed().choose(schema.enumConstants());
        }

        // 5. Priority 4: Type-based generation
        String type = schema.type() != null ? schema.type().toLowerCase() : "object";
        String format = schema.format() != null ? schema.format().toLowerCase() : "";

        return switch (type) {
            case "string" -> {
                if ("date".equals(format) || "date-time".equals(format)) {
                    yield dateTimeValueGenerator.generate(schema, context);
                }
                yield stringValueGenerator.generate(schema, context);
            }
            case "integer", "number" -> numberValueGenerator.generate(schema, context);
            case "boolean" -> booleanValueGenerator.generate(schema, context);
            case "array" -> collectionValueGenerator.generateArray(schema, context);
            case "object" -> collectionValueGenerator.generateObject(schema, context);
            default -> stringValueGenerator.generate(schema, context);
        };
    }

    private String extractRefName(String ref) {
        if (ref == null) return null;
        if (ref.startsWith("#/components/schemas/")) {
            return ref.substring("#/components/schemas/".length());
        }
        int lastSlash = ref.lastIndexOf('/');
        return lastSlash >= 0 ? ref.substring(lastSlash + 1) : ref;
    }

    private NormalizedSchema resolveRef(String refName, NormalizedContract contract) {
        if (contract != null && contract.schemas() != null) {
            return contract.schemas().get(refName);
        }
        return null;
    }
}
