package com.mockapilab.modules.runtime.generation.generators;

import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.runtime.generation.DataGenerationContext;
import com.mockapilab.modules.runtime.generation.GenerationSeed;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Generator for realistic and deterministic numeric values (integers and decimals) with constraint bounds.
 */
@Component
public class NumberValueGenerator {

    public Number generate(NormalizedSchema schema, DataGenerationContext context) {
        String type = schema.type() != null ? schema.type().toLowerCase() : "number";
        String format = schema.format() != null ? schema.format().toLowerCase() : "";
        String prop = context.getPropertyName() != null ? context.getPropertyName().toLowerCase() : "";
        GenerationSeed seed = context.getSeed();

        boolean isInteger = "integer".equals(type) || "int32".equals(format) || "int64".equals(format);

        Double minBound = schema.minimum();
        Double maxBound = schema.maximum();

        if (isInteger) {
            int min = minBound != null ? minBound.intValue() : 1;
            int max = maxBound != null ? maxBound.intValue() : 100;

            if (minBound == null && maxBound == null) {
                if (prop.contains("age")) {
                    min = 18;
                    max = 75;
                } else if (prop.contains("port")) {
                    min = 1000;
                    max = 9000;
                } else if (prop.contains("count") || prop.contains("quantity") || prop.contains("total") || prop.contains("size")) {
                    min = 1;
                    max = 50;
                } else if (prop.contains("page")) {
                    min = 1;
                    max = 10;
                } else if (prop.contains("year")) {
                    min = 2020;
                    max = 2026;
                } else if (prop.contains("month")) {
                    min = 1;
                    max = 12;
                } else if (prop.contains("day")) {
                    min = 1;
                    max = 28;
                }
            }

            if (max < min) max = min;
            return seed.nextInt(min, max);
        } else {
            double min = minBound != null ? minBound : 1.0;
            double max = maxBound != null ? maxBound : 1000.0;

            if (minBound == null && maxBound == null) {
                if (prop.contains("price") || prop.contains("amount") || prop.contains("cost")) {
                    min = 9.99;
                    max = 499.99;
                } else if (prop.contains("rating") || prop.contains("score")) {
                    min = 1.0;
                    max = 5.0;
                } else if (prop.contains("percentage") || prop.contains("discount")) {
                    min = 5.0;
                    max = 50.0;
                }
            }

            if (max < min) max = min;
            double val = seed.nextDouble(min, max);
            return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP).doubleValue();
        }
    }
}
