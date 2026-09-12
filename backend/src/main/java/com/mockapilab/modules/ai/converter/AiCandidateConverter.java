package com.mockapilab.modules.ai.converter;

import com.mockapilab.modules.ai.model.candidate.AiCandidateContract;
import com.mockapilab.modules.ai.model.candidate.AiCandidateEndpoint;
import com.mockapilab.modules.ai.model.candidate.AiCandidateParameter;
import com.mockapilab.modules.ai.model.candidate.AiCandidateRequestBody;
import com.mockapilab.modules.ai.model.candidate.AiCandidateResponse;
import com.mockapilab.modules.ai.model.candidate.AiCandidateSchema;
import com.mockapilab.modules.contract.model.normalized.ContractMetadata;
import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import com.mockapilab.modules.contract.model.normalized.NormalizedMediaType;
import com.mockapilab.modules.contract.model.normalized.NormalizedParameter;
import com.mockapilab.modules.contract.model.normalized.NormalizedRequestBody;
import com.mockapilab.modules.contract.model.normalized.NormalizedResponse;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.contract.model.normalized.ParameterLocation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministically converts validated AiCandidateContract into the canonical NormalizedContract representation.
 */
@Component
public class AiCandidateConverter {

    public NormalizedContract convert(AiCandidateContract candidate) {
        String title = StringUtils.hasText(candidate.title()) ? candidate.title().trim() : "Extracted API";
        String description = candidate.description();
        String version = StringUtils.hasText(candidate.version()) ? candidate.version().trim() : "1.0.0";
        ContractMetadata metadata = ContractMetadata.of(title, description, version);

        // Convert Schemas
        Map<String, NormalizedSchema> schemas = new LinkedHashMap<>();
        if (candidate.schemas() != null) {
            candidate.schemas().forEach((name, candidateSchema) -> {
                schemas.put(name, convertSchema(candidateSchema));
            });
        }

        // Convert Endpoints
        List<NormalizedEndpoint> endpoints = new ArrayList<>();
        if (candidate.endpoints() != null) {
            for (AiCandidateEndpoint ep : candidate.endpoints()) {
                endpoints.add(convertEndpoint(ep));
            }
        }

        return new NormalizedContract(metadata, endpoints, schemas);
    }

    private NormalizedEndpoint convertEndpoint(AiCandidateEndpoint endpoint) {
        String path = endpoint.path().trim();
        String method = endpoint.method().trim().toUpperCase();
        String operationId = endpoint.operationId();
        String summary = endpoint.summary();
        String description = endpoint.description();

        List<NormalizedParameter> parameters = new ArrayList<>();
        if (endpoint.parameters() != null) {
            for (AiCandidateParameter p : endpoint.parameters()) {
                parameters.add(convertParameter(p));
            }
        }

        NormalizedRequestBody requestBody = null;
        if (endpoint.requestBody() != null) {
            requestBody = convertRequestBody(endpoint.requestBody());
        }

        List<NormalizedResponse> responses = new ArrayList<>();
        if (endpoint.responses() != null) {
            for (AiCandidateResponse r : endpoint.responses()) {
                responses.add(convertResponse(r));
            }
        }

        return new NormalizedEndpoint(
                path,
                method,
                operationId,
                summary,
                description,
                parameters,
                requestBody,
                responses
        );
    }

    private NormalizedParameter convertParameter(AiCandidateParameter parameter) {
        ParameterLocation location = switch (parameter.in() != null ? parameter.in().trim().toLowerCase() : "query") {
            case "path" -> ParameterLocation.PATH;
            case "query" -> ParameterLocation.QUERY;
            case "header" -> ParameterLocation.HEADER;
            case "cookie" -> ParameterLocation.COOKIE;
            default -> ParameterLocation.QUERY;
        };

        boolean required = Boolean.TRUE.equals(parameter.required()) || location == ParameterLocation.PATH;
        NormalizedSchema schema = convertSchema(parameter.schema());

        return new NormalizedParameter(
                parameter.name().trim(),
                location,
                required,
                parameter.description(),
                schema
        );
    }

    private NormalizedRequestBody convertRequestBody(AiCandidateRequestBody requestBody) {
        Map<String, NormalizedMediaType> contentTypes = new LinkedHashMap<>();
        String ct = StringUtils.hasText(requestBody.contentType()) ? requestBody.contentType().trim() : "application/json";
        NormalizedSchema schema = convertSchema(requestBody.schema());
        contentTypes.put(ct, new NormalizedMediaType(schema, null));

        return new NormalizedRequestBody(
                requestBody.description(),
                Boolean.TRUE.equals(requestBody.required()),
                contentTypes
        );
    }

    private NormalizedResponse convertResponse(AiCandidateResponse response) {
        Map<String, NormalizedMediaType> contentTypes = new LinkedHashMap<>();
        if (response.schema() != null) {
            String ct = StringUtils.hasText(response.contentType()) ? response.contentType().trim() : "application/json";
            NormalizedSchema schema = convertSchema(response.schema());
            contentTypes.put(ct, new NormalizedMediaType(schema, null));
        }

        return new NormalizedResponse(
                response.statusCode().trim(),
                response.description(),
                contentTypes.isEmpty() ? null : contentTypes,
                null
        );
    }

    private NormalizedSchema convertSchema(AiCandidateSchema schema) {
        if (schema == null) {
            return null;
        }

        if (StringUtils.hasText(schema.ref())) {
            return NormalizedSchema.ref(normalizeRefName(schema.ref()));
        }

        String type = schema.type() != null ? schema.type().trim().toLowerCase() : "object";
        String format = schema.format();
        String description = schema.description();
        Boolean nullable = schema.nullable();
        Object defaultValue = schema.defaultValue();
        Object example = schema.example();
        List<String> enumConstants = schema.enumConstants() != null ? new ArrayList<>(schema.enumConstants()) : null;

        Map<String, NormalizedSchema> properties = null;
        if (schema.properties() != null) {
            properties = new LinkedHashMap<>();
            for (Map.Entry<String, AiCandidateSchema> entry : schema.properties().entrySet()) {
                properties.put(entry.getKey(), convertSchema(entry.getValue()));
            }
        }

        List<String> requiredProperties = schema.requiredProperties() != null ? new ArrayList<>(schema.requiredProperties()) : null;
        NormalizedSchema items = schema.items() != null ? convertSchema(schema.items()) : null;

        return new NormalizedSchema(
                type,
                format,
                description,
                nullable,
                defaultValue,
                example,
                enumConstants,
                properties,
                requiredProperties,
                items,
                null,
                schema.minimum(),
                schema.maximum(),
                schema.minLength(),
                schema.maxLength(),
                schema.pattern()
        );
    }

    private String normalizeRefName(String ref) {
        if (ref == null) return null;
        if (ref.startsWith("#/components/schemas/")) {
            return ref.substring("#/components/schemas/".length());
        }
        int lastSlash = ref.lastIndexOf('/');
        return lastSlash >= 0 ? ref.substring(lastSlash + 1) : ref;
    }
}