package com.mockapilab.modules.runtime.engine;

import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import com.mockapilab.modules.contract.model.normalized.NormalizedMediaType;
import com.mockapilab.modules.contract.model.normalized.NormalizedResponse;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic schema-driven response generator for OpenAPI mock responses.
 */
@Component
public class DeterministicResponseGenerator {

    private static final int MAX_DEPTH = 5;

    public Object generateResponsePayload(NormalizedEndpoint endpoint, String targetStatusCode, NormalizedContract contract) {
        NormalizedResponse response = findResponse(endpoint, targetStatusCode);
        if (response == null || response.contentTypes() == null || response.contentTypes().isEmpty()) {
            return null;
        }

        NormalizedMediaType mediaType = response.contentTypes().get("application/json");
        if (mediaType == null) {
            mediaType = response.contentTypes().values().iterator().next();
        }

        if (mediaType.example() != null) {
            return mediaType.example();
        }

        if (mediaType.schema() == null) {
            return null;
        }

        return generateFromSchema(mediaType.schema(), null, contract, 0);
    }

    public Object generateFromSchema(NormalizedSchema schema, String propertyName, NormalizedContract contract, int depth) {
        if (schema == null || depth > MAX_DEPTH) {
            return null;
        }

        if (schema.example() != null) {
            return schema.example();
        }

        if (schema.defaultValue() != null) {
            return schema.defaultValue();
        }

        NormalizedSchema resolved = resolveRef(schema, contract);
        if (resolved != schema) {
            return generateFromSchema(resolved, propertyName, contract, depth + 1);
        }

        String type = resolved.type() != null ? resolved.type().toLowerCase() : "object";

        if (resolved.enumConstants() != null && !resolved.enumConstants().isEmpty()) {
            return resolved.enumConstants().get(0);
        }

        return switch (type) {
            case "string" -> generateStringValue(resolved, propertyName);
            case "integer" -> generateIntegerValue(resolved, propertyName);
            case "number" -> generateNumberValue(resolved);
            case "boolean" -> true;
            case "array" -> {
                List<Object> list = new ArrayList<>();
                if (resolved.items() != null) {
                    list.add(generateFromSchema(resolved.items(), propertyName != null ? propertyName + "Item" : "item", contract, depth + 1));
                }
                yield list;
            }
            case "object" -> {
                Map<String, Object> obj = new LinkedHashMap<>();
                if (resolved.properties() != null) {
                    for (Map.Entry<String, NormalizedSchema> entry : resolved.properties().entrySet()) {
                        obj.put(entry.getKey(), generateFromSchema(entry.getValue(), entry.getKey(), contract, depth + 1));
                    }
                }
                yield obj;
            }
            default -> "mock-value";
        };
    }

    private String generateStringValue(NormalizedSchema schema, String propertyName) {
        String format = schema.format() != null ? schema.format().toLowerCase() : "";
        if ("uuid".equals(format) || (propertyName != null && propertyName.toLowerCase().endsWith("id"))) {
            return UUID.randomUUID().toString();
        }
        if ("email".equals(format) || (propertyName != null && propertyName.toLowerCase().contains("email"))) {
            return "user@example.com";
        }
        if ("date-time".equals(format) || (propertyName != null && (propertyName.toLowerCase().contains("time") || propertyName.toLowerCase().contains("at")))) {
            return "2026-01-01T12:00:00Z";
        }
        if ("date".equals(format) || (propertyName != null && propertyName.toLowerCase().contains("date"))) {
            return "2026-01-01";
        }
        if ("uri".equals(format) || "url".equals(format) || (propertyName != null && propertyName.toLowerCase().contains("url"))) {
            return "https://example.com/api";
        }

        if (propertyName != null) {
            String lower = propertyName.toLowerCase();
            if (lower.contains("name")) return "Sample " + capitalize(propertyName);
            if (lower.contains("title")) return "Sample Title";
            if (lower.contains("desc")) return "Sample description for " + propertyName;
            if (lower.contains("status")) return "ACTIVE";
            if (lower.contains("code")) return "MOCK_CODE";
            return "sample_" + propertyName;
        }

        return "sample_text";
    }

    private Number generateIntegerValue(NormalizedSchema schema, String propertyName) {
        if (propertyName != null) {
            String lower = propertyName.toLowerCase();
            if (lower.contains("count") || lower.contains("total")) return 10;
            if (lower.contains("page")) return 1;
            if (lower.contains("age")) return 25;
            if (lower.contains("port")) return 8080;
        }
        return 1;
    }

    private Number generateNumberValue(NormalizedSchema schema) {
        return 99.99;
    }

    private NormalizedResponse findResponse(NormalizedEndpoint endpoint, String targetStatusCode) {
        if (endpoint.responses() == null || endpoint.responses().isEmpty()) {
            return null;
        }

        for (NormalizedResponse resp : endpoint.responses()) {
            if (targetStatusCode.equals(resp.statusCode())) {
                return resp;
            }
        }

        // Return first 2xx
        for (NormalizedResponse resp : endpoint.responses()) {
            if (resp.statusCode() != null && resp.statusCode().startsWith("2")) {
                return resp;
            }
        }

        return endpoint.responses().get(0);
    }

    private NormalizedSchema resolveRef(NormalizedSchema schema, NormalizedContract contract) {
        if (schema == null) {
            return null;
        }
        if (schema.ref() != null && contract != null && contract.schemas() != null) {
            String refName = schema.ref();
            if (refName.startsWith("#/components/schemas/")) {
                refName = refName.substring("#/components/schemas/".length());
            }
            NormalizedSchema resolved = contract.schemas().get(refName);
            if (resolved != null) {
                return resolved;
            }
        }
        return schema;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}