package com.mockapilab.modules.contract.model.normalized;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Normalized representation of data schemas (types, formats, constraints, objects, arrays, and enums).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedSchema(
        String type,
        String format,
        String description,
        Boolean nullable,
        Object defaultValue,
        Object example,
        List<String> enumConstants,
        Map<String, NormalizedSchema> properties,
        List<String> requiredProperties,
        NormalizedSchema items,
        String ref
) {
    public static NormalizedSchema string(String format, String description) {
        return new NormalizedSchema("string", format, description, false, null, null, null, null, null, null, null);
    }

    public static NormalizedSchema integer(String format, String description) {
        return new NormalizedSchema("integer", format, description, false, null, null, null, null, null, null, null);
    }

    public static NormalizedSchema number(String format, String description) {
        return new NormalizedSchema("number", format, description, false, null, null, null, null, null, null, null);
    }

    public static NormalizedSchema booleanType(String description) {
        return new NormalizedSchema("boolean", null, description, false, null, null, null, null, null, null, null);
    }

    public static NormalizedSchema array(NormalizedSchema items, String description) {
        return new NormalizedSchema("array", null, description, false, null, null, null, null, null, items, null);
    }

    public static NormalizedSchema object(Map<String, NormalizedSchema> properties, List<String> requiredProperties, String description) {
        return new NormalizedSchema("object", null, description, false, null, null, null, properties, requiredProperties, null, null);
    }

    public static NormalizedSchema enumeration(List<String> enumConstants, String description) {
        return new NormalizedSchema("string", null, description, false, null, null, enumConstants, null, null, null, null);
    }

    public static NormalizedSchema ref(String ref) {
        return new NormalizedSchema(null, null, null, false, null, null, null, null, null, null, ref);
    }
}
