package com.mockapilab.modules.runtime.engine;

import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles NormalizedEndpoints from a NormalizedContract into a list of prioritized CompiledRoute instances.
 */
@Component
public class RouteCompiler {

    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^}]+)\\}");

    public List<CompiledRoute> compile(NormalizedContract contract) {
        if (contract == null || contract.endpoints() == null) {
            return Collections.emptyList();
        }

        List<CompiledRoute> routes = new ArrayList<>();
        for (NormalizedEndpoint endpoint : contract.endpoints()) {
            routes.add(compileEndpoint(endpoint));
        }

        // Sort by specificity score descending
        Collections.sort(routes);
        return routes;
    }

    public CompiledRoute compileEndpoint(NormalizedEndpoint endpoint) {
        String originalPath = normalizePath(endpoint.path());
        String method = endpoint.method().toUpperCase();

        List<String> paramNames = new ArrayList<>();
        Matcher matcher = PATH_PARAM_PATTERN.matcher(originalPath);
        while (matcher.find()) {
            paramNames.add(matcher.group(1));
        }

        // Build regex: replace {param} with ([^/]+)
        String regexString = "^" + PATH_PARAM_PATTERN.matcher(originalPath).replaceAll("([^/]+)") + "/?$";
        Pattern pattern = Pattern.compile(regexString, Pattern.CASE_INSENSITIVE);

        // Analyze segments for REST route classification
        String[] segments = originalPath.split("/");
        List<String> nonEmptySegments = new ArrayList<>();
        for (String s : segments) {
            if (!s.isBlank()) {
                nonEmptySegments.add(s);
            }
        }

        int specificityScore = 0;
        for (String s : nonEmptySegments) {
            if (s.startsWith("{") && s.endsWith("}")) {
                specificityScore += 1;
            } else {
                specificityScore += 10;
            }
        }

        RouteType routeType = RouteType.GENERIC;
        String collectionPattern = null;
        String idParameterName = null;

        if (!nonEmptySegments.isEmpty()) {
            String lastSegment = nonEmptySegments.get(nonEmptySegments.size() - 1);
            boolean lastIsParam = lastSegment.startsWith("{") && lastSegment.endsWith("}");

            if (lastIsParam) {
                idParameterName = lastSegment.substring(1, lastSegment.length() - 1);
                // Collection is all preceding segments
                StringBuilder colBuilder = new StringBuilder();
                for (int i = 0; i < nonEmptySegments.size() - 1; i++) {
                    colBuilder.append("/").append(nonEmptySegments.get(i));
                }
                collectionPattern = colBuilder.length() == 0 ? "/" : colBuilder.toString();

                if ("GET".equals(method)) {
                    routeType = RouteType.ENTITY_GET;
                } else if ("PUT".equals(method) || "PATCH".equals(method)) {
                    routeType = RouteType.ENTITY_UPDATE;
                } else if ("DELETE".equals(method)) {
                    routeType = RouteType.ENTITY_DELETE;
                }
            } else {
                StringBuilder colBuilder = new StringBuilder();
                for (String s : nonEmptySegments) {
                    colBuilder.append("/").append(s);
                }
                collectionPattern = colBuilder.toString();

                if ("GET".equals(method)) {
                    routeType = RouteType.COLLECTION_LIST;
                } else if ("POST".equals(method)) {
                    routeType = RouteType.COLLECTION_CREATE;
                }
            }
        }

        return new CompiledRoute(
                endpoint,
                method,
                originalPath,
                pattern,
                paramNames,
                routeType,
                collectionPattern,
                idParameterName,
                specificityScore
        );
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}