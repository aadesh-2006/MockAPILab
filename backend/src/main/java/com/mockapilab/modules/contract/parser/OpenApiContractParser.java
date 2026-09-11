package com.mockapilab.modules.contract.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.mockapilab.modules.contract.model.normalized.ContractMetadata;
import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import com.mockapilab.modules.contract.model.normalized.NormalizedHeader;
import com.mockapilab.modules.contract.model.normalized.NormalizedMediaType;
import com.mockapilab.modules.contract.model.normalized.NormalizedParameter;
import com.mockapilab.modules.contract.model.normalized.NormalizedRequestBody;
import com.mockapilab.modules.contract.model.normalized.NormalizedResponse;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.contract.model.normalized.ParameterLocation;
import com.mockapilab.modules.contract.validation.ContractValidationException;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dedicated parser for converting OpenAPI 3.x (JSON/YAML) definitions into the normalized contract model.
 */
@Component
public class OpenApiContractParser {

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final YAMLMapper yamlMapper = new YAMLMapper();

    public NormalizedContract parse(String rawContent) {
        if (!StringUtils.hasText(rawContent)) {
            throw new ContractValidationException("OpenAPI specification content cannot be empty");
        }

        validateRawFormatAndVersion(rawContent);

        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(false);

        SwaggerParseResult parseResult = new OpenAPIParser().readContents(rawContent, null, options);
        OpenAPI openAPI = parseResult.getOpenAPI();

        if (openAPI == null) {
            String errorDetails = parseResult.getMessages() != null && !parseResult.getMessages().isEmpty()
                    ? String.join("; ", parseResult.getMessages())
                    : "Malformed or unparseable OpenAPI specification";
            throw new ContractValidationException("Failed to parse OpenAPI document: " + errorDetails);
        }

        validateOpenApiVersion(openAPI);
        validateReferences(openAPI);

        ContractMetadata metadata = extractMetadata(openAPI);
        Map<String, NormalizedSchema> schemas = extractSchemas(openAPI);
        List<NormalizedEndpoint> endpoints = extractEndpoints(openAPI);

        return new NormalizedContract(metadata, endpoints, schemas);
    }

    private void validateRawFormatAndVersion(String rawContent) {
        try {
            JsonNode rootNode;
            if (rawContent.trim().startsWith("{")) {
                rootNode = jsonMapper.readTree(rawContent);
            } else {
                rootNode = yamlMapper.readTree(rawContent);
            }

            if (rootNode == null || !rootNode.isObject()) {
                throw new ContractValidationException("OpenAPI specification must be a valid JSON or YAML object");
            }

            if (rootNode.has("swagger")) {
                String swaggerVer = rootNode.get("swagger").asText();
                throw new ContractValidationException("Unsupported Swagger version: " + swaggerVer + ". MockAPILab requires OpenAPI 3.x");
            }

            if (!rootNode.has("openapi")) {
                throw new ContractValidationException("Missing 'openapi' version field. MockAPILab requires OpenAPI 3.x");
            }

            String openApiVer = rootNode.get("openapi").asText();
            if (!openApiVer.startsWith("3.")) {
                throw new ContractValidationException("Unsupported OpenAPI version: " + openApiVer + ". MockAPILab requires OpenAPI 3.x");
            }
        } catch (ContractValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ContractValidationException("Malformed or unparseable JSON/YAML: " + e.getMessage(), e);
        }
    }

    private void validateOpenApiVersion(OpenAPI openAPI) {
        String version = openAPI.getOpenapi();
        if (!StringUtils.hasText(version)) {
            throw new ContractValidationException("Missing OpenAPI specification version. MockAPILab requires OpenAPI 3.x");
        }
        if (!version.startsWith("3.")) {
            throw new ContractValidationException("Unsupported OpenAPI version: " + version + ". MockAPILab requires OpenAPI 3.x");
        }
    }

    private void validateReferences(OpenAPI openAPI) {
        if (openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) {
            return;
        }
        Set<String> definedSchemas = openAPI.getComponents().getSchemas().keySet();

        // Validate references in paths and component schemas
        if (openAPI.getPaths() != null) {
            openAPI.getPaths().forEach((path, pathItem) -> {
                for (Operation op : getOperations(pathItem).values()) {
                    if (op.getParameters() != null) {
                        for (Parameter param : op.getParameters()) {
                            checkSchemaRef(param.getSchema(), definedSchemas);
                        }
                    }
                    if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
                        for (MediaType mt : op.getRequestBody().getContent().values()) {
                            checkSchemaRef(mt.getSchema(), definedSchemas);
                        }
                    }
                    if (op.getResponses() != null) {
                        for (ApiResponse resp : op.getResponses().values()) {
                            if (resp.getContent() != null) {
                                for (MediaType mt : resp.getContent().values()) {
                                    checkSchemaRef(mt.getSchema(), definedSchemas);
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    private void checkSchemaRef(Schema<?> schema, Set<String> definedSchemas) {
        if (schema == null) return;
        if (schema.get$ref() != null) {
            String refName = extractRefName(schema.get$ref());
            if (!definedSchemas.contains(refName)) {
                throw new ContractValidationException("Unresolved reference: " + schema.get$ref());
            }
        }
        if (schema.getProperties() != null) {
            for (Schema<?> prop : schema.getProperties().values()) {
                checkSchemaRef(prop, definedSchemas);
            }
        }
        if (schema instanceof ArraySchema arraySchema && arraySchema.getItems() != null) {
            checkSchemaRef(arraySchema.getItems(), definedSchemas);
        }
    }

    private ContractMetadata extractMetadata(OpenAPI openAPI) {
        String title = "Untitled API";
        String description = null;
        String version = "1.0.0";

        if (openAPI.getInfo() != null) {
            if (StringUtils.hasText(openAPI.getInfo().getTitle())) {
                title = openAPI.getInfo().getTitle();
            }
            description = openAPI.getInfo().getDescription();
            if (StringUtils.hasText(openAPI.getInfo().getVersion())) {
                version = openAPI.getInfo().getVersion();
            }
        }

        return ContractMetadata.of(title, description, version);
    }

    private Map<String, NormalizedSchema> extractSchemas(OpenAPI openAPI) {
        Map<String, NormalizedSchema> schemas = new LinkedHashMap<>();
        if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
            openAPI.getComponents().getSchemas().forEach((name, schema) -> {
                schemas.put(name, normalizeSchema(schema));
            });
        }
        return schemas;
    }

    private NormalizedSchema normalizeSchema(Schema<?> schema) {
        if (schema == null) {
            return null;
        }

        if (schema.get$ref() != null) {
            return NormalizedSchema.ref(extractRefName(schema.get$ref()));
        }

        String type = schema.getType();
        if (type == null) {
            if (schema.getProperties() != null) {
                type = "object";
            } else if (schema instanceof ArraySchema || schema.getItems() != null) {
                type = "array";
            } else if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
                type = "string";
            } else {
                type = "object";
            }
        }

        String format = schema.getFormat();
        String description = schema.getDescription();
        Boolean nullable = schema.getNullable();
        Object defaultValue = schema.getDefault();
        Object example = schema.getExample();

        List<String> enumConstants = null;
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            enumConstants = schema.getEnum().stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
        }

        Map<String, NormalizedSchema> properties = null;
        if (schema.getProperties() != null) {
            properties = new LinkedHashMap<>();
            for (Map.Entry<String, Schema> entry : schema.getProperties().entrySet()) {
                properties.put(entry.getKey(), normalizeSchema(entry.getValue()));
            }
        }

        List<String> requiredProperties = schema.getRequired() != null ? new ArrayList<>(schema.getRequired()) : null;

        NormalizedSchema items = null;
        if (schema instanceof ArraySchema arraySchema && arraySchema.getItems() != null) {
            items = normalizeSchema(arraySchema.getItems());
        } else if (schema.getItems() != null) {
            items = normalizeSchema(schema.getItems());
        }

        Double minimum = schema.getMinimum() != null ? schema.getMinimum().doubleValue() : null;
        Double maximum = schema.getMaximum() != null ? schema.getMaximum().doubleValue() : null;
        Integer minLength = schema.getMinLength();
        Integer maxLength = schema.getMaxLength();
        String pattern = schema.getPattern();

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
                minimum,
                maximum,
                minLength,
                maxLength,
                pattern
        );
    }

    private List<NormalizedEndpoint> extractEndpoints(OpenAPI openAPI) {
        List<NormalizedEndpoint> endpoints = new ArrayList<>();

        if (openAPI.getPaths() == null) {
            return endpoints;
        }

        for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();

            List<Parameter> pathLevelParameters = pathItem.getParameters() != null ? pathItem.getParameters() : Collections.emptyList();
            Map<String, Operation> operations = getOperations(pathItem);

            for (Map.Entry<String, Operation> opEntry : operations.entrySet()) {
                String httpMethod = opEntry.getKey();
                Operation operation = opEntry.getValue();

                List<NormalizedParameter> parameters = new ArrayList<>();

                // Path-level parameters
                for (Parameter p : pathLevelParameters) {
                    parameters.add(normalizeParameter(p));
                }

                // Operation-level parameters
                if (operation.getParameters() != null) {
                    for (Parameter p : operation.getParameters()) {
                        parameters.add(normalizeParameter(p));
                    }
                }

                // Request body
                NormalizedRequestBody requestBody = null;
                if (operation.getRequestBody() != null) {
                    requestBody = normalizeRequestBody(operation.getRequestBody());
                }

                // Responses
                List<NormalizedResponse> responses = normalizeResponses(operation.getResponses());

                NormalizedEndpoint endpoint = new NormalizedEndpoint(
                        path,
                        httpMethod,
                        operation.getOperationId(),
                        operation.getSummary(),
                        operation.getDescription(),
                        parameters,
                        requestBody,
                        responses
                );

                endpoints.add(endpoint);
            }
        }

        return endpoints;
    }

    private Map<String, Operation> getOperations(PathItem pathItem) {
        Map<String, Operation> ops = new LinkedHashMap<>();
        if (pathItem.getGet() != null) ops.put("GET", pathItem.getGet());
        if (pathItem.getPost() != null) ops.put("POST", pathItem.getPost());
        if (pathItem.getPut() != null) ops.put("PUT", pathItem.getPut());
        if (pathItem.getDelete() != null) ops.put("DELETE", pathItem.getDelete());
        if (pathItem.getPatch() != null) ops.put("PATCH", pathItem.getPatch());
        if (pathItem.getOptions() != null) ops.put("OPTIONS", pathItem.getOptions());
        if (pathItem.getHead() != null) ops.put("HEAD", pathItem.getHead());
        return ops;
    }

    private NormalizedParameter normalizeParameter(Parameter parameter) {
        ParameterLocation location = switch (parameter.getIn() != null ? parameter.getIn().toLowerCase() : "") {
            case "path" -> ParameterLocation.PATH;
            case "query" -> ParameterLocation.QUERY;
            case "header" -> ParameterLocation.HEADER;
            case "cookie" -> ParameterLocation.COOKIE;
            default -> ParameterLocation.QUERY;
        };

        boolean required = Boolean.TRUE.equals(parameter.getRequired()) || location == ParameterLocation.PATH;
        NormalizedSchema schema = normalizeSchema(parameter.getSchema());

        return new NormalizedParameter(
                parameter.getName(),
                location,
                required,
                parameter.getDescription(),
                schema
        );
    }

    private NormalizedRequestBody normalizeRequestBody(RequestBody requestBody) {
        Map<String, NormalizedMediaType> contentTypes = new LinkedHashMap<>();
        if (requestBody.getContent() != null) {
            for (Map.Entry<String, MediaType> entry : requestBody.getContent().entrySet()) {
                MediaType mediaType = entry.getValue();
                NormalizedSchema schema = normalizeSchema(mediaType.getSchema());
                contentTypes.put(entry.getKey(), new NormalizedMediaType(schema, mediaType.getExample()));
            }
        }

        return new NormalizedRequestBody(
                requestBody.getDescription(),
                Boolean.TRUE.equals(requestBody.getRequired()),
                contentTypes
        );
    }

    private List<NormalizedResponse> normalizeResponses(ApiResponses responses) {
        List<NormalizedResponse> result = new ArrayList<>();
        if (responses == null) {
            return result;
        }

        for (Map.Entry<String, ApiResponse> entry : responses.entrySet()) {
            String statusCode = entry.getKey();
            ApiResponse response = entry.getValue();

            Map<String, NormalizedMediaType> contentTypes = new LinkedHashMap<>();
            if (response.getContent() != null) {
                for (Map.Entry<String, MediaType> ctEntry : response.getContent().entrySet()) {
                    MediaType mediaType = ctEntry.getValue();
                    NormalizedSchema schema = normalizeSchema(mediaType.getSchema());
                    contentTypes.put(ctEntry.getKey(), new NormalizedMediaType(schema, mediaType.getExample()));
                }
            }

            Map<String, NormalizedHeader> headers = new LinkedHashMap<>();
            if (response.getHeaders() != null) {
                for (Map.Entry<String, Header> hEntry : response.getHeaders().entrySet()) {
                    Header header = hEntry.getValue();
                    headers.put(hEntry.getKey(), new NormalizedHeader(
                            hEntry.getKey(),
                            header.getDescription(),
                            normalizeSchema(header.getSchema())
                    ));
                }
            }

            result.add(new NormalizedResponse(
                    statusCode,
                    response.getDescription(),
                    contentTypes,
                    headers.isEmpty() ? null : headers
            ));
        }

        return result;
    }

    private String extractRefName(String ref) {
        if (ref == null) return null;
        if (ref.startsWith("#/components/schemas/")) {
            return ref.substring("#/components/schemas/".length());
        }
        int lastSlash = ref.lastIndexOf('/');
        return lastSlash >= 0 ? ref.substring(lastSlash + 1) : ref;
    }
}
