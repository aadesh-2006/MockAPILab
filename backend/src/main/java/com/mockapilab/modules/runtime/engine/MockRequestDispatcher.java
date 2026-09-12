package com.mockapilab.modules.runtime.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import com.mockapilab.modules.contract.model.normalized.NormalizedMediaType;
import com.mockapilab.modules.contract.model.normalized.NormalizedResponse;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.runtime.model.MockRuntime;
import com.mockapilab.modules.runtime.model.MockRuntimeStatus;
import com.mockapilab.modules.runtime.observability.MockApiLabMetrics;
import com.mockapilab.modules.runtime.repository.MockRuntimeRepository;
import com.mockapilab.modules.runtime.state.RuntimeStateStore;
import com.mockapilab.modules.scenario.engine.ScenarioEngine;
import com.mockapilab.modules.scenario.engine.ScenarioEvaluationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Core dynamic mock request dispatcher.
 * <p>
 * Routes incoming HTTP requests for a given runtime ID to the compiled route table,
 * enforces schema validations, evaluates scenario failure injection rules, executes isolated stateful REST operations,
 * and records runtime execution metrics.
 */
@Component
public class MockRequestDispatcher {

    private final RuntimeRegistry runtimeRegistry;
    private final MockRuntimeRepository runtimeRepository;
    private final RuntimeStateStore stateStore;
    private final RouteCompiler routeCompiler;
    private final MockRequestValidator requestValidator;
    private final DeterministicResponseGenerator responseGenerator;
    private final ScenarioEngine scenarioEngine;
    private final ObjectMapper objectMapper;
    private final MockApiLabMetrics metrics;

    public MockRequestDispatcher(
            RuntimeRegistry runtimeRegistry,
            MockRuntimeRepository runtimeRepository,
            RuntimeStateStore stateStore,
            RouteCompiler routeCompiler,
            MockRequestValidator requestValidator,
            DeterministicResponseGenerator responseGenerator,
            ScenarioEngine scenarioEngine,
            ObjectMapper objectMapper
    ) {
        this(runtimeRegistry, runtimeRepository, stateStore, routeCompiler, requestValidator, responseGenerator, scenarioEngine, objectMapper, null);
    }

    @Autowired
    public MockRequestDispatcher(
            RuntimeRegistry runtimeRegistry,
            MockRuntimeRepository runtimeRepository,
            RuntimeStateStore stateStore,
            RouteCompiler routeCompiler,
            MockRequestValidator requestValidator,
            DeterministicResponseGenerator responseGenerator,
            ScenarioEngine scenarioEngine,
            ObjectMapper objectMapper,
            @Autowired(required = false) MockApiLabMetrics metrics
    ) {
        this.runtimeRegistry = runtimeRegistry;
        this.runtimeRepository = runtimeRepository;
        this.stateStore = stateStore;
        this.routeCompiler = routeCompiler;
        this.requestValidator = requestValidator;
        this.responseGenerator = responseGenerator;
        this.scenarioEngine = scenarioEngine;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public ResponseEntity<Object> dispatch(
            UUID runtimeId,
            String httpMethod,
            String subPath,
            String rawBody,
            Map<String, String> queryParams,
            Map<String, String> headers
    ) {
        long startTime = System.currentTimeMillis();
        ResponseEntity<Object> response = doDispatch(runtimeId, httpMethod, subPath, rawBody, queryParams, headers);
        long durationMs = System.currentTimeMillis() - startTime;

        if (metrics != null) {
            metrics.recordMockRequest(runtimeId, httpMethod, response.getStatusCode().value(), durationMs);
        }

        return response;
    }

    private ResponseEntity<Object> doDispatch(
            UUID runtimeId,
            String httpMethod,
            String subPath,
            String rawBody,
            Map<String, String> queryParams,
            Map<String, String> headers
    ) {
        // 1. Resolve active runtime instance
        RuntimeInstance instance = resolveRuntimeInstance(runtimeId);
        if (instance == null) {
            Map<String, Object> error = Map.of(
                    "status", HttpStatus.NOT_FOUND.value(),
                    "error", "Mock runtime '" + runtimeId + "' is not running or does not exist."
            );
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(error);
        }

        String normalizedPath = normalizeSubPath(subPath);

        // 2. Find matching compiled route
        CompiledRoute matchedRoute = null;
        boolean pathMatchedWithDifferentMethod = false;

        for (CompiledRoute route : instance.compiledRoutes()) {
            if (route.matches(httpMethod, normalizedPath)) {
                matchedRoute = route;
                break;
            } else if (route.getRegexPattern().matcher(normalizedPath).matches()) {
                pathMatchedWithDifferentMethod = true;
            }
        }

        if (matchedRoute == null) {
            if (pathMatchedWithDifferentMethod) {
                Map<String, Object> error = Map.of(
                        "status", HttpStatus.METHOD_NOT_ALLOWED.value(),
                        "error", "Method " + httpMethod + " not allowed for path " + normalizedPath
                );
                return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).contentType(MediaType.APPLICATION_JSON).body(error);
            }

            Map<String, Object> error = Map.of(
                    "status", HttpStatus.NOT_FOUND.value(),
                    "error", "Route not found in mock contract: " + httpMethod + " " + normalizedPath
            );
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(error);
        }

        // 3. Extract path variables
        Map<String, String> pathVariables = matchedRoute.extractPathVariables(normalizedPath);

        // 4. Parse request body if present
        Object parsedBody = null;
        if (rawBody != null && !rawBody.isBlank()) {
            try {
                parsedBody = objectMapper.readValue(rawBody, Object.class);
            } catch (Exception e) {
                Map<String, Object> error = Map.of(
                        "status", HttpStatus.BAD_REQUEST.value(),
                        "error", "Malformed JSON request body: " + e.getMessage()
                );
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(error);
            }
        }

        // 5. Validate request against endpoint schema
        ValidationResult validation = requestValidator.validate(matchedRoute.getEndpoint(), parsedBody, instance.contract());
        if (!validation.valid()) {
            Map<String, Object> error = Map.of(
                    "status", HttpStatus.BAD_REQUEST.value(),
                    "error", "Request validation failed",
                    "details", validation.errors()
            );
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(error);
        }

        // 6. Evaluate active scenario rules and failure injection
        ScenarioEvaluationResult scenarioResult = scenarioEngine.evaluateAndExecute(runtimeId, httpMethod, normalizedPath);
        if (scenarioResult.shouldShortCircuit()) {
            return scenarioResult.injectedResponse();
        }

        // 7. Execute stateful REST semantics
        return executeRoute(runtimeId, instance, matchedRoute, normalizedPath, pathVariables, parsedBody);
    }

    private RuntimeInstance resolveRuntimeInstance(UUID runtimeId) {
        Optional<RuntimeInstance> active = runtimeRegistry.get(runtimeId);
        if (active.isPresent()) {
            return active.get();
        }

        // Lazy load from DB if status is RUNNING
        Optional<MockRuntime> runtimeOpt = runtimeRepository.findById(runtimeId);
        if (runtimeOpt.isPresent()) {
            MockRuntime runtime = runtimeOpt.get();
            if (runtime.getStatus() == MockRuntimeStatus.RUNNING) {
                NormalizedContract contract = runtime.getContractVersion().getNormalizedDefinition();
                List<CompiledRoute> routes = routeCompiler.compile(contract);
                RuntimeInstance instance = new RuntimeInstance(
                        runtime.getId(),
                        runtime.getProject().getId(),
                        runtime.getContractVersion().getId(),
                        contract,
                        routes,
                        runtime.getStartedAt() != null ? runtime.getStartedAt() : runtime.getCreatedAt()
                );
                runtimeRegistry.register(instance);
                return instance;
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Object> executeRoute(
            UUID runtimeId,
            RuntimeInstance instance,
            CompiledRoute route,
            String requestPath,
            Map<String, String> pathVariables,
            Object body
    ) {
        String collectionPath = route.resolveCollectionPath(pathVariables);
        RouteType routeType = route.getRouteType();

        switch (routeType) {
            case COLLECTION_CREATE -> {
                Map<String, Object> entity = body instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
                String entityId = extractOrGenerateId(entity, route.getIdParameterName());
                entity.put("id", entityId);

                Map<String, Object> saved = stateStore.saveEntity(runtimeId, collectionPath, entityId, entity);

                int statusCode = resolveStatusCode(route.getEndpoint(), "201", HttpStatus.CREATED.value());
                return ResponseEntity.status(statusCode).contentType(MediaType.APPLICATION_JSON).body(saved);
            }

            case COLLECTION_LIST -> {
                List<Map<String, Object>> collection = stateStore.getCollection(runtimeId, collectionPath);
                int statusCode = resolveStatusCode(route.getEndpoint(), "200", HttpStatus.OK.value());
                return ResponseEntity.status(statusCode).contentType(MediaType.APPLICATION_JSON).body(collection);
            }

            case ENTITY_GET -> {
                String entityId = pathVariables.get(route.getIdParameterName());
                if (entityId == null && !pathVariables.isEmpty()) {
                    entityId = pathVariables.values().iterator().next();
                }

                Optional<Map<String, Object>> entityOpt = stateStore.getEntity(runtimeId, collectionPath, entityId);
                if (entityOpt.isPresent()) {
                    int statusCode = resolveStatusCode(route.getEndpoint(), "200", HttpStatus.OK.value());
                    return ResponseEntity.status(statusCode).contentType(MediaType.APPLICATION_JSON).body(entityOpt.get());
                }

                Map<String, Object> notFound = Map.of(
                    "status", HttpStatus.NOT_FOUND.value(),
                    "error", "Entity with ID '" + entityId + "' not found in " + collectionPath
                );
                return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(notFound);
            }

            case ENTITY_UPDATE -> {
                String entityId = pathVariables.get(route.getIdParameterName());
                if (entityId == null && !pathVariables.isEmpty()) {
                    entityId = pathVariables.values().iterator().next();
                }

                Optional<Map<String, Object>> existing = stateStore.getEntity(runtimeId, collectionPath, entityId);
                if (existing.isEmpty()) {
                    Map<String, Object> notFound = Map.of(
                            "status", HttpStatus.NOT_FOUND.value(),
                            "error", "Entity with ID '" + entityId + "' not found in " + collectionPath
                    );
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(notFound);
                }

                Map<String, Object> merged = new LinkedHashMap<>(existing.get());
                if (body instanceof Map<?, ?> m) {
                    merged.putAll((Map<String, Object>) m);
                }
                merged.put("id", entityId);

                Map<String, Object> saved = stateStore.saveEntity(runtimeId, collectionPath, entityId, merged);
                int statusCode = resolveStatusCode(route.getEndpoint(), "200", HttpStatus.OK.value());
                return ResponseEntity.status(statusCode).contentType(MediaType.APPLICATION_JSON).body(saved);
            }

            case ENTITY_DELETE -> {
                String entityId = pathVariables.get(route.getIdParameterName());
                if (entityId == null && !pathVariables.isEmpty()) {
                    entityId = pathVariables.values().iterator().next();
                }

                boolean deleted = stateStore.deleteEntity(runtimeId, collectionPath, entityId);
                if (!deleted) {
                    Map<String, Object> notFound = Map.of(
                            "status", HttpStatus.NOT_FOUND.value(),
                            "error", "Entity with ID '" + entityId + "' not found in " + collectionPath
                    );
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(notFound);
                }

                int statusCode = resolveStatusCode(route.getEndpoint(), "204", HttpStatus.NO_CONTENT.value());
                if (statusCode == HttpStatus.NO_CONTENT.value()) {
                    return ResponseEntity.noContent().build();
                }
                return ResponseEntity.status(statusCode).contentType(MediaType.APPLICATION_JSON).body(Map.of("message", "Deleted successfully"));
            }

            case GENERIC -> {
                int statusCode = resolveStatusCode(route.getEndpoint(), "200", HttpStatus.OK.value());
                Object payload = responseGenerator.generateResponsePayload(route.getEndpoint(), String.valueOf(statusCode), instance.contract());
                if (payload == null) {
                    payload = Collections.emptyMap();
                }
                return ResponseEntity.status(statusCode).contentType(MediaType.APPLICATION_JSON).body(payload);
            }
        }

        return ResponseEntity.ok().build();
    }

    private String extractOrGenerateId(Map<String, Object> entity, String idFieldName) {
        if (idFieldName != null && entity.containsKey(idFieldName) && entity.get(idFieldName) != null) {
            return String.valueOf(entity.get(idFieldName));
        }
        if (entity.containsKey("id") && entity.get("id") != null) {
            return String.valueOf(entity.get("id"));
        }
        return UUID.randomUUID().toString();
    }

    private int resolveStatusCode(NormalizedEndpoint endpoint, String preferred, int defaultCode) {
        if (endpoint.responses() != null) {
            for (NormalizedResponse resp : endpoint.responses()) {
                if (preferred.equals(resp.statusCode())) {
                    try {
                        return Integer.parseInt(preferred);
                    } catch (NumberFormatException ignored) {}
                }
            }
            for (NormalizedResponse resp : endpoint.responses()) {
                if (resp.statusCode() != null && resp.statusCode().startsWith("2")) {
                    try {
                        return Integer.parseInt(resp.statusCode());
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return defaultCode;
    }

    private String normalizeSubPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}