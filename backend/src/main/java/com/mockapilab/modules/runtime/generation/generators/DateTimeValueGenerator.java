package com.mockapilab.modules.runtime.generation.generators;

import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.runtime.generation.DataGenerationContext;
import com.mockapilab.modules.runtime.generation.GenerationSeed;
import org.springframework.stereotype.Component;

/**
 * Generator for realistic and deterministic ISO-8601 timestamps and calendar dates.
 */
@Component
public class DateTimeValueGenerator {

    public String generate(NormalizedSchema schema, DataGenerationContext context) {
        String format = schema.format() != null ? schema.format().toLowerCase() : "date-time";
        GenerationSeed seed = context.getSeed();

        int year = seed.nextInt(2025, 2026);
        int month = seed.nextInt(1, 12);
        int day = seed.nextInt(1, 28);
        int hour = seed.nextInt(0, 23);
        int min = seed.nextInt(0, 59);
        int sec = seed.nextInt(0, 59);

        if ("date".equals(format)) {
            return String.format("%04d-%02d-%02d", year, month, day);
        }

        return String.format("%04d-%02d-%02dT%02d:%02d:%02dZ", year, month, day, hour, min, sec);
    }
}
