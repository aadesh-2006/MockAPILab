package com.mockapilab.modules.runtime.engine;

import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import com.mockapilab.modules.contract.model.normalized.NormalizedMediaType;
import com.mockapilab.modules.contract.model.normalized.NormalizedRequestBody;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates incoming mock requests against OpenAPI contract constraints.
 */
@Component
public class MockRequestValidator {

    public ValidationResult validate(
            NormalizedEndpoint endpoint,
            Object body,
            NormalizedContract contract
    ) {
        NormalizedRequestBody requestBody = endpoint.requestBody();
        if (requestBody == null) {
            return ValidationResult.ok();
        }

        if (requestBody.required() && (body == null || (body instanceof String s && s.isBlank()))) {
            return ValidationResult.fail("Request body is required for " + endpoint.method() + " " + endpoint.path());
        }

        if (body == null) {
            return ValidationResult.ok();
        }

        NormalizedSchema schema = resolveRequestBodySchema(requestBody, contract);
        if (schema == null) {
            return ValidationResult.ok();
        }

        List<String> errors = new ArrayList<>();
        validateSchema(body, schema, "", contract, errors);

        if (errors.isEmpty()) {
            return ValidationResult.ok();
        }
        return ValidationResult.fail(errors);
    }

    private NormalizedSchema resolveRequestBodySchema(NormalizedRequestBody requestBody, NormalizedContract contract) {
        if (requestBody.contentTypes() == null || requestBody.contentTypes().isEmpty()) {
            return null;
        }

        NormalizedMediaType mediaType = requestBody.contentTypes().get("application/json");
        if (mediaType == null) {
            // Pick first available
            mediaType = requestBody.contentTypes().values().iterator().next();
        }

        if (mediaType == null || mediaType.schema() == null) {
            return null;
        }

        return resolveRef(mediaType.schema(), contract);
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

    @SuppressWarnings("unchecked")
    private void validateSchema(
            Object value,
            NormalizedSchema schema,
            String path,
            NormalizedContract contract,
            List<String> errors
    ) {
        if (value == null) {
            if (Boolean.FALSE.equals(schema.nullable())) {
                errors.add("Field '" + (path.isEmpty() ? "body" : path) + "' cannot be null");
            }
            return;
        }

        schema = resolveRef(schema, contract);
        String expectedType = schema.type() != null ? schema.type().toLowerCase() : "object";

        switch (expectedType) {
            case "object" -> {
                if (!(value instanceof Map<?, ?> map)) {
                    errors.add("Field '" + (path.isEmpty() ? "body" : path) + "' must be an object, but got " + value.getClass().getSimpleName());
                    return;
                }

                // Check required properties
                if (schema.requiredProperties() != null) {
                    for (String reqProp : schema.requiredProperties()) {
                        if (!map.containsKey(reqProp) || map.get(reqProp) == null) {
                            String fieldPath = path.isEmpty() ? reqProp : path + "." + reqProp;
                            errors.add("Missing required property: '" + fieldPath + "'");
                        }
                    }
                }

                // Validate individual properties if schema defines them
                if (schema.properties() != null) {
                    Map<String, Object> castMap = (Map<String, Object>) map;
                    for (Map.Entry<String, Object> entry : castMap.entrySet()) {
                        String propKey = entry.getKey();
                        NormalizedSchema propSchema = schema.properties().get(propKey);
                        if (propSchema != null) {
                            String fieldPath = path.isEmpty() ? propKey : path + "." + propKey;
                            validateSchema(entry.getValue(), propSchema, fieldPath, contract, errors);
                        }
                    }
                }
            }
            case "array" -> {
                if (!(value instanceof List<?> list)) {
                    errors.add("Field '" + (path.isEmpty() ? "body" : path) + "' must be an array, but got " + value.getClass().getSimpleName());
                    return;
                }
                if (schema.items() != null) {
                    for (int i = 0; i < list.size(); i++) {
                        String itemPath = path + "[" + i + "]";
                        validateSchema(list.get(i), schema.items(), itemPath, contract, errors);
                    }
                }
            }
            case "string" -> {
                if (!(value instanceof String strVal)) {
                    errors.add("Field '" + path + "' must be a string, but got " + value.getClass().getSimpleName());
                    return;
                }
                if (schema.enumConstants() != null && !schema.enumConstants().isEmpty()) {
                    if (!schema.enumConstants().contains(strVal)) {
                        errors.add("Field '" + path + "' value '" + strVal + "' is not one of allowed enum values: " + schema.enumConstants());
                    }
                }
            }
            case "integer" -> {
                if (!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)) {
                    errors.add("Field '" + path + "' must be an integer, but got " + value.getClass().getSimpleName());
                }
            }
            case "number" -> {
                if (!(value instanceof Number)) {
                    errors.add("Field '" + path + "' must be a number, but got " + value.getClass().getSimpleName());
                }
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) {
                    errors.add("Field '" + path + "' must be a boolean, but got " + value.getClass().getSimpleName());
                }
            }
        }
    }
}