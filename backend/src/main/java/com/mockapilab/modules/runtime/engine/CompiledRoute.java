package com.mockapilab.modules.runtime.engine;

import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An executable, compiled route representing an endpoint in a NormalizedContract.
 */
public class CompiledRoute implements Comparable<CompiledRoute> {

    private final NormalizedEndpoint endpoint;
    private final String httpMethod;
    private final String originalPath;
    private final Pattern regexPattern;
    private final List<String> pathParameterNames;
    private final RouteType routeType;
    private final String collectionPattern;
    private final String idParameterName;
    private final int specificityScore;

    public CompiledRoute(
            NormalizedEndpoint endpoint,
            String httpMethod,
            String originalPath,
            Pattern regexPattern,
            List<String> pathParameterNames,
            RouteType routeType,
            String collectionPattern,
            String idParameterName,
            int specificityScore
    ) {
        this.endpoint = endpoint;
        this.httpMethod = httpMethod.toUpperCase();
        this.originalPath = originalPath;
        this.regexPattern = regexPattern;
        this.pathParameterNames = pathParameterNames;
        this.routeType = routeType;
        this.collectionPattern = collectionPattern;
        this.idParameterName = idParameterName;
        this.specificityScore = specificityScore;
    }

    public boolean matches(String method, String requestPath) {
        if (!this.httpMethod.equalsIgnoreCase(method)) {
            return false;
        }
        return regexPattern.matcher(requestPath).matches();
    }

    public Map<String, String> extractPathVariables(String requestPath) {
        Matcher matcher = regexPattern.matcher(requestPath);
        if (!matcher.matches()) {
            return Collections.emptyMap();
        }

        Map<String, String> vars = new LinkedHashMap<>();
        for (int i = 0; i < pathParameterNames.size(); i++) {
            if (i + 1 <= matcher.groupCount()) {
                vars.put(pathParameterNames.get(i), matcher.group(i + 1));
            }
        }
        return vars;
    }

    /**
     * Resolves the concrete collection path given the extracted path variables.
     * E.g. for /users/{userId}/orders, replaces {userId} with the actual value 123 to produce /users/123/orders.
     */
    public String resolveCollectionPath(Map<String, String> pathVariables) {
        if (collectionPattern == null) {
            return null;
        }
        String resolved = collectionPattern;
        for (Map.Entry<String, String> entry : pathVariables.entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
    }

    public NormalizedEndpoint getEndpoint() {
        return endpoint;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getOriginalPath() {
        return originalPath;
    }

    public Pattern getRegexPattern() {
        return regexPattern;
    }

    public List<String> getPathParameterNames() {
        return pathParameterNames;
    }

    public RouteType getRouteType() {
        return routeType;
    }

    public String getCollectionPattern() {
        return collectionPattern;
    }

    public String getIdParameterName() {
        return idParameterName;
    }

    public int getSpecificityScore() {
        return specificityScore;
    }

    @Override
    public int compareTo(CompiledRoute other) {
        // Higher specificity score should come first (descending)
        int cmp = Integer.compare(other.specificityScore, this.specificityScore);
        if (cmp != 0) {
            return cmp;
        }
        return this.originalPath.compareTo(other.originalPath);
    }
}