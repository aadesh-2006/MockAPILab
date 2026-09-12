package com.mockapilab.modules.contract.drift.engine;

import com.mockapilab.modules.contract.drift.model.ContractDriftChange;
import com.mockapilab.modules.contract.drift.model.DriftChangeType;
import com.mockapilab.modules.contract.drift.model.DriftClassification;
import com.mockapilab.modules.contract.drift.model.DriftSeverity;
import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import com.mockapilab.modules.contract.model.normalized.NormalizedMediaType;
import com.mockapilab.modules.contract.model.normalized.NormalizedParameter;
import com.mockapilab.modules.contract.model.normalized.NormalizedRequestBody;
import com.mockapilab.modules.contract.model.normalized.NormalizedResponse;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic diff engine that analyzes differences between two immutable NormalizedContract versions.
 * Employs cycle-safe recursive schema comparison and canonical ordering normalization.
 */
@Component
public class ContractDiffEngine {

    private final DriftClassifier classifier;

    public ContractDiffEngine(DriftClassifier classifier) {
        this.classifier = classifier;
    }

    public record DiffResult(
            List<ContractDriftChange> changes,
            int breakingCount,
            int nonBreakingCount,
            int informationalCount,
            DriftSeverity overallSeverity
    ) {}

    public DiffResult diff(NormalizedContract fromContract, NormalizedContract toContract) {
        List<ContractDriftChange> changes = new ArrayList<>();
        Set<String> visitedComparisons = new HashSet<>();

        // 1. Compare Metadata
        compareMetadata(fromContract, toContract, changes);

        // 2. Compare Endpoints
        compareEndpoints(fromContract, toContract, changes, visitedComparisons);

        // 3. Compare Reusable Schemas / Components
        compareNamedSchemas(fromContract, toContract, changes, visitedComparisons);

        // Calculate summary metrics
        int breakingCount = 0;
        int nonBreakingCount = 0;
        int informationalCount = 0;
        List<DriftChangeType> changeTypes = new ArrayList<>();

        for (ContractDriftChange change : changes) {
            changeTypes.add(change.getChangeType());
            if (change.getClassification() == DriftClassification.BREAKING) {
                breakingCount++;
            } else if (change.getClassification() == DriftClassification.NON_BREAKING) {
                nonBreakingCount++;
            } else if (change.getClassification() == DriftClassification.INFORMATIONAL) {
                informationalCount++;
            }
        }

        DriftSeverity overallSeverity = classifier.calculateOverallSeverity(
                breakingCount, nonBreakingCount, informationalCount, changeTypes
        );

        return new DiffResult(changes, breakingCount, nonBreakingCount, informationalCount, overallSeverity);
    }

    private void compareMetadata(NormalizedContract from, NormalizedContract to, List<ContractDriftChange> changes) {
        if (from.metadata() == null && to.metadata() == null) {
            return;
        }

        String fromTitle = from.metadata() != null ? from.metadata().title() : null;
        String toTitle = to.metadata() != null ? to.metadata().title() : null;
        if (!Objects.equals(fromTitle, toTitle)) {
            addChange(changes, DriftChangeType.METADATA_CHANGED, DriftClassifier.SchemaContext.COMPONENT,
                    null, null, "metadata.title", fromTitle, toTitle,
                    "Contract title updated from '" + fromTitle + "' to '" + toTitle + "'", false, false);
        }

        String fromVer = from.metadata() != null ? from.metadata().version() : null;
        String toVer = to.metadata() != null ? to.metadata().version() : null;
        if (!Objects.equals(fromVer, toVer)) {
            addChange(changes, DriftChangeType.METADATA_CHANGED, DriftClassifier.SchemaContext.COMPONENT,
                    null, null, "metadata.version", fromVer, toVer,
                    "Contract specification version changed from '" + fromVer + "' to '" + toVer + "'", false, false);
        }
    }

    private void compareEndpoints(
            NormalizedContract from,
            NormalizedContract to,
            List<ContractDriftChange> changes,
            Set<String> visitedComparisons
    ) {
        Map<String, NormalizedEndpoint> fromMap = toNormalizedEndpointMap(from.endpoints());
        Map<String, NormalizedEndpoint> toMap = toNormalizedEndpointMap(to.endpoints());

        // Check for removed endpoints (or removed methods)
        for (Map.Entry<String, NormalizedEndpoint> entry : fromMap.entrySet()) {
            String key = entry.getKey();
            NormalizedEndpoint fromEp = entry.getValue();

            if (!toMap.containsKey(key)) {
                addChange(changes, DriftChangeType.ENDPOINT_REMOVED, DriftClassifier.SchemaContext.COMPONENT,
                        fromEp.path(), fromEp.method(), "endpoint",
                        fromEp.method() + " " + fromEp.path(), null,
                        "Endpoint removed: " + fromEp.method() + " " + fromEp.path(), true, true);
            } else {
                NormalizedEndpoint toEp = toMap.get(key);
                compareEndpointDetails(fromEp, toEp, changes, visitedComparisons);
            }
        }

        // Check for added endpoints
        for (Map.Entry<String, NormalizedEndpoint> entry : toMap.entrySet()) {
            String key = entry.getKey();
            NormalizedEndpoint toEp = entry.getValue();

            if (!fromMap.containsKey(key)) {
                addChange(changes, DriftChangeType.ENDPOINT_ADDED, DriftClassifier.SchemaContext.COMPONENT,
                        toEp.path(), toEp.method(), "endpoint",
                        null, toEp.method() + " " + toEp.path(),
                        "New endpoint added: " + toEp.method() + " " + toEp.path(), false, false);
            }
        }
    }

    private void compareEndpointDetails(
            NormalizedEndpoint fromEp,
            NormalizedEndpoint toEp,
            List<ContractDriftChange> changes,
            Set<String> visitedComparisons
    ) {
        String path = fromEp.path();
        String method = fromEp.method();

        // 1. Parameters
        compareParameters(path, method, fromEp.parameters(), toEp.parameters(), changes, visitedComparisons);

        // 2. Request Body
        compareRequestBody(path, method, fromEp.requestBody(), toEp.requestBody(), changes, visitedComparisons);

        // 3. Responses
        compareResponses(path, method, fromEp.responses(), toEp.responses(), changes, visitedComparisons);
    }

    private void compareParameters(
            String path,
            String method,
            List<NormalizedParameter> fromParams,
            List<NormalizedParameter> toParams,
            List<ContractDriftChange> changes,
            Set<String> visitedComparisons
    ) {
        Map<String, NormalizedParameter> fromMap = toNormalizedParamMap(fromParams);
        Map<String, NormalizedParameter> toMap = toNormalizedParamMap(toParams);

        // Removed parameters
        for (Map.Entry<String, NormalizedParameter> entry : fromMap.entrySet()) {
            String key = entry.getKey();
            NormalizedParameter fromParam = entry.getValue();

            if (!toMap.containsKey(key)) {
                addChange(changes, DriftChangeType.PARAMETER_REMOVED, DriftClassifier.SchemaContext.PARAMETER,
                        path, method, "parameter:" + fromParam.name() + " (" + fromParam.location() + ")",
                        fromParam.name() + " (required=" + fromParam.required() + ")", null,
                        "Parameter removed: " + fromParam.name() + " (" + fromParam.location() + ")", false, fromParam.required());
            } else {
                NormalizedParameter toParam = toMap.get(key);
                compareSingleParameter(path, method, fromParam, toParam, changes, visitedComparisons);
            }
        }

        // Added parameters
        for (Map.Entry<String, NormalizedParameter> entry : toMap.entrySet()) {
            String key = entry.getKey();
            NormalizedParameter toParam = entry.getValue();

            if (!fromMap.containsKey(key)) {
                addChange(changes, DriftChangeType.PARAMETER_ADDED, DriftClassifier.SchemaContext.PARAMETER,
                        path, method, "parameter:" + toParam.name() + " (" + toParam.location() + ")",
                        null, toParam.name() + " (required=" + toParam.required() + ")",
                        "Parameter added: " + toParam.name() + " (" + toParam.location() + ", required=" + toParam.required() + ")",
                        toParam.required(), false);
            }
        }
    }

    private void compareSingleParameter(
            String path,
            String method,
            NormalizedParameter from,
            NormalizedParameter to,
            List<ContractDriftChange> changes,
            Set<String> visitedComparisons
    ) {
        String loc = "parameter:" + from.name();

        if (from.required() != to.required()) {
            addChange(changes, DriftChangeType.PARAMETER_REQUIRED_CHANGED, DriftClassifier.SchemaContext.PARAMETER,
                    path, method, loc + ".required",
                    String.valueOf(from.required()), String.valueOf(to.required()),
                    "Parameter '" + from.name() + "' required changed from " + from.required() + " to " + to.required(),
                    to.required(), from.required());
        }

        if (from.location() != to.location()) {
            addChange(changes, DriftChangeType.PARAMETER_LOCATION_CHANGED, DriftClassifier.SchemaContext.PARAMETER,
                    path, method, loc + ".location",
                    String.valueOf(from.location()), String.valueOf(to.location()),
                    "Parameter '" + from.name() + "' location changed from " + from.location() + " to " + to.location(),
                    to.required(), from.required());
        }

        if (from.schema() != null && to.schema() != null) {
            compareSchemas(path, method, loc + ".schema", from.schema(), to.schema(),
                    DriftClassifier.SchemaContext.PARAMETER, changes, visitedComparisons);
        } else if (from.schema() != null && to.schema() == null) {
            addChange(changes, DriftChangeType.PARAMETER_TYPE_CHANGED, DriftClassifier.SchemaContext.PARAMETER,
                    path, method, loc + ".schema", from.schema().type(), null,
                    "Parameter '" + from.name() + "' schema definition removed", to.required(), from.required());
        } else if (from.schema() == null && to.schema() != null) {
            addChange(changes, DriftChangeType.PARAMETER_TYPE_CHANGED, DriftClassifier.SchemaContext.PARAMETER,
                    path, method, loc + ".schema", null, to.schema().type(),
                    "Parameter '" + from.name() + "' schema definition added", to.required(), from.required());
        }
    }

    private void compareRequestBody(
            String path,
            String method,
            NormalizedRequestBody fromBody,
            NormalizedRequestBody toBody,
            List<ContractDriftChange> changes,
            Set<String> visitedComparisons
    ) {
        if (fromBody == null && toBody == null) {
            return;
        }

        if (fromBody != null && toBody == null) {
            addChange(changes, DriftChangeType.REQUEST_BODY_REMOVED, DriftClassifier.SchemaContext.REQUEST,
                    path, method, "requestBody",
                    "requestBody (required=" + fromBody.required() + ")", null,
                    "Request body removed for " + method + " " + path, false, fromBody.required());
            return;
        }

        if (fromBody == null && toBody != null) {
            addChange(changes, DriftChangeType.REQUEST_BODY_ADDED, DriftClassifier.SchemaContext.REQUEST,
                    path, method, "requestBody",
                    null, "requestBody (required=" + toBody.required() + ")",
                    "Request body added for " + method + " " + path + " (required=" + toBody.required() + ")",
                    toBody.required(), false);
            return;
        }

        // Both present
        if (fromBody.required() != toBody.required()) {
            addChange(changes, DriftChangeType.REQUEST_SCHEMA_CHANGED, DriftClassifier.SchemaContext.REQUEST,
                    path, method, "requestBody.required",
                    String.valueOf(fromBody.required()), String.valueOf(toBody.required()),
                    "Request body required changed from " + fromBody.required() + " to " + toBody.required(),
                    toBody.required(), fromBody.required());
        }

        Map<String, NormalizedMediaType> fromMedia = fromBody.contentTypes() != null ? fromBody.contentTypes() : Map.of();
        Map<String, NormalizedMediaType> toMedia = toBody.contentTypes() != null ? toBody.contentTypes() : Map.of();

        for (Map.Entry<String, NormalizedMediaType> entry : fromMedia.entrySet()) {
            String mediaType = entry.getKey();
            NormalizedMediaType fromMt = entry.getValue();

            if (!toMedia.containsKey(mediaType)) {
                addChange(changes, DriftChangeType.REQUEST_SCHEMA_CHANGED, DriftClassifier.SchemaContext.REQUEST,
                        path, method, "requestBody.content[" + mediaType + "]",
                        mediaType, null,
                        "Request media type '" + mediaType + "' removed", true, true);
            } else {
                NormalizedMediaType toMt = toMedia.get(mediaType);
                if (fromMt.schema() != null && toMt.schema() != null) {
                    compareSchemas(path, method, "requestBody.content[" + mediaType + "].schema",
                            fromMt.schema(), toMt.schema(), DriftClassifier.SchemaContext.REQUEST, changes, visitedComparisons);
                }
            }
        }

        for (Map.Entry<String, NormalizedMediaType> entry : toMedia.entrySet()) {
            String mediaType = entry.getKey();
            if (!fromMedia.containsKey(mediaType)) {
                addChange(changes, DriftChangeType.REQUEST_SCHEMA_CHANGED, DriftClassifier.SchemaContext.REQUEST,
                        path, method, "requestBody.content[" + mediaType + "]",
                        null, mediaType,
                        "Request media type '" + mediaType + "' added", false, false);
            }
        }
    }

    private void compareResponses(
            String path,
            String method,
            List<NormalizedResponse> fromResponses,
            List<NormalizedResponse> toResponses,
            List<ContractDriftChange> changes,
            Set<String> visitedComparisons
    ) {
        Map<String, NormalizedResponse> fromMap = toNormalizedResponseMap(fromResponses);
        Map<String, NormalizedResponse> toMap = toNormalizedResponseMap(toResponses);

        // Removed responses
        for (Map.Entry<String, NormalizedResponse> entry : fromMap.entrySet()) {
            String code = entry.getKey();
            NormalizedResponse fromResp = entry.getValue();

            if (!toMap.containsKey(code)) {
                addChange(changes, DriftChangeType.RESPONSE_STATUS_REMOVED, DriftClassifier.SchemaContext.RESPONSE,
                        path, method, "responses[" + code + "]",
                        code, null,
                        "Response status code " + code + " removed", true, true);
            } else {
                NormalizedResponse toResp = toMap.get(code);
                compareSingleResponse(path, method, code, fromResp, toResp, changes, visitedComparisons);
            }
        }

        // Added responses
        for (Map.Entry<String, NormalizedResponse> entry : toMap.entrySet()) {
            String code = entry.getKey();
            if (!fromMap.containsKey(code)) {
                addChange(changes, DriftChangeType.RESPONSE_STATUS_ADDED, DriftClassifier.SchemaContext.RESPONSE,
                        path, method, "responses[" + code + "]",
                        null, code,
                        "Response status code " + code + " added", false, false);
            }
        }
    }

    private void compareSingleResponse(
            String path,
            String method,
            String statusCode,
            NormalizedResponse fromResp,
            NormalizedResponse toResp,
            List<ContractDriftChange> changes,
            Set<String> visitedComparisons
    ) {
        Map<String, NormalizedMediaType> fromMedia = fromResp.contentTypes() != null ? fromResp.contentTypes() : Map.of();
        Map<String, NormalizedMediaType> toMedia = toResp.contentTypes() != null ? toResp.contentTypes() : Map.of();

        for (Map.Entry<String, NormalizedMediaType> entry : fromMedia.entrySet()) {
            String mediaType = entry.getKey();
            NormalizedMediaType fromMt = entry.getValue();

            if (!toMedia.containsKey(mediaType)) {
                addChange(changes, DriftChangeType.RESPONSE_SCHEMA_CHANGED, DriftClassifier.SchemaContext.RESPONSE,
                        path, method, "responses[" + statusCode + "].content[" + mediaType + "]",
                        mediaType, null,
                        "Response media type '" + mediaType + "' removed for status " + statusCode, true, true);
            } else {
                NormalizedMediaType toMt = toMedia.get(mediaType);
                if (fromMt.schema() != null && toMt.schema() != null) {
                    compareSchemas(path, method, "responses[" + statusCode + "].content[" + mediaType + "].schema",
                            fromMt.schema(), toMt.schema(), DriftClassifier.SchemaContext.RESPONSE, changes, visitedComparisons);
                }
            }
        }

        for (Map.Entry<String, NormalizedMediaType> entry : toMedia.entrySet()) {
            String mediaType = entry.getKey();
            if (!fromMedia.containsKey(mediaType)) {
                addChange(changes, DriftChangeType.RESPONSE_SCHEMA_CHANGED, DriftClassifier.SchemaContext.RESPONSE,
                        path, method, "responses[" + statusCode + "].content[" + mediaType + "]",
                        null, mediaType,
                        "Response media type '" + mediaType + "' added for status " + statusCode, false, false);
            }
        }
    }

    private void compareNamedSchemas(
            NormalizedContract from,
            NormalizedContract to,
            List<ContractDriftChange> changes,
            Set<String> visitedComparisons
    ) {
        Map<String, NormalizedSchema> fromSchemas = from.schemas() != null ? from.schemas() : Map.of();
        Map<String, NormalizedSchema> toSchemas = to.schemas() != null ? to.schemas() : Map.of();

        for (Map.Entry<String, NormalizedSchema> entry : fromSchemas.entrySet()) {
            String name = entry.getKey();
            NormalizedSchema fromSchema = entry.getValue();

            if (!toSchemas.containsKey(name)) {
                addChange(changes, DriftChangeType.PROPERTY_REMOVED, DriftClassifier.SchemaContext.COMPONENT,
                        null, null, "components.schemas." + name,
                        name, null,
                        "Schema model '" + name + "' removed", true, true);
            } else {
                NormalizedSchema toSchema = toSchemas.get(name);
                compareSchemas(null, null, "components.schemas." + name, fromSchema, toSchema,
                        DriftClassifier.SchemaContext.COMPONENT, changes, visitedComparisons);
            }
        }

        for (Map.Entry<String, NormalizedSchema> entry : toSchemas.entrySet()) {
            String name = entry.getKey();
            if (!fromSchemas.containsKey(name)) {
                addChange(changes, DriftChangeType.PROPERTY_ADDED, DriftClassifier.SchemaContext.COMPONENT,
                        null, null, "components.schemas." + name,
                        null, name,
                        "Schema model '" + name + "' added", false, false);
            }
        }
    }

    private void compareSchemas(
            String path,
            String method,
            String location,
            NormalizedSchema from,
            NormalizedSchema to,
            DriftClassifier.SchemaContext context,
            List<ContractDriftChange> changes,
            Set<String> visitedComparisons
    ) {
        if (from == null && to == null) {
            return;
        }
        if (from == null) {
            addChange(changes, DriftChangeType.PROPERTY_ADDED, context, path, method, location,
                    null, to.type(), "Schema added at " + location, false, false);
            return;
        }
        if (to == null) {
            addChange(changes, DriftChangeType.PROPERTY_REMOVED, context, path, method, location,
                    from.type(), null, "Schema removed at " + location, true, true);
            return;
        }

        // Cycle protection: Generate comparison signature
        String cycleKey = location + "::" + System.identityHashCode(from) + "<->" + System.identityHashCode(to);
        if (visitedComparisons.contains(cycleKey)) {
            return;
        }
        visitedComparisons.add(cycleKey);

        // 1. Schema Type change
        if (!Objects.equals(from.type(), to.type())) {
            addChange(changes, DriftChangeType.PROPERTY_TYPE_CHANGED, context, path, method, location + ".type",
                    from.type(), to.type(),
                    "Type changed from '" + from.type() + "' to '" + to.type() + "' at " + location, true, true);
        }

        // 2. Format change
        if (!Objects.equals(from.format(), to.format())) {
            addChange(changes, DriftChangeType.PROPERTY_TYPE_CHANGED, context, path, method, location + ".format",
                    from.format(), to.format(),
                    "Format changed from '" + from.format() + "' to '" + to.format() + "' at " + location, true, false);
        }

        // 3. Enum constants
        compareEnumConstants(path, method, location, from.enumConstants(), to.enumConstants(), context, changes);

        // 4. Constraints (min, max, minLength, maxLength, pattern)
        compareConstraints(path, method, location, from, to, context, changes);

        // 5. Array items
        if (from.items() != null || to.items() != null) {
            compareSchemas(path, method, location + "[]", from.items(), to.items(), context, changes, visitedComparisons);
        }

        // 6. Object properties & required fields
        compareObjectProperties(path, method, location, from, to, context, changes, visitedComparisons);
    }

    private void compareEnumConstants(
            String path,
            String method,
            String location,
            List<String> fromEnums,
            List<String> toEnums,
            DriftClassifier.SchemaContext context,
            List<ContractDriftChange> changes
    ) {
        if (fromEnums == null && toEnums == null) return;
        Set<String> fromSet = fromEnums != null ? new TreeSet<>(fromEnums) : Collections.emptySet();
        Set<String> toSet = toEnums != null ? new TreeSet<>(toEnums) : Collections.emptySet();

        for (String removedEnum : fromSet) {
            if (!toSet.contains(removedEnum)) {
                addChange(changes, DriftChangeType.ENUM_VALUE_REMOVED, context, path, method, location + ".enum",
                        removedEnum, null,
                        "Enum constant '" + removedEnum + "' removed at " + location, true, true);
            }
        }

        for (String addedEnum : toSet) {
            if (!fromSet.contains(addedEnum)) {
                addChange(changes, DriftChangeType.ENUM_VALUE_ADDED, context, path, method, location + ".enum",
                        null, addedEnum,
                        "Enum constant '" + addedEnum + "' added at " + location, false, false);
            }
        }
    }

    private void compareConstraints(
            String path,
            String method,
            String location,
            NormalizedSchema from,
            NormalizedSchema to,
            DriftClassifier.SchemaContext context,
            List<ContractDriftChange> changes
    ) {
        if (!Objects.equals(from.minimum(), to.minimum())) {
            addChange(changes, DriftChangeType.CONSTRAINT_CHANGED, context, path, method, location + ".minimum",
                    String.valueOf(from.minimum()), String.valueOf(to.minimum()),
                    "Minimum constraint changed from " + from.minimum() + " to " + to.minimum() + " at " + location, false, false);
        }
        if (!Objects.equals(from.maximum(), to.maximum())) {
            addChange(changes, DriftChangeType.CONSTRAINT_CHANGED, context, path, method, location + ".maximum",
                    String.valueOf(from.maximum()), String.valueOf(to.maximum()),
                    "Maximum constraint changed from " + from.maximum() + " to " + to.maximum() + " at " + location, false, false);
        }
        if (!Objects.equals(from.minLength(), to.minLength())) {
            addChange(changes, DriftChangeType.CONSTRAINT_CHANGED, context, path, method, location + ".minLength",
                    String.valueOf(from.minLength()), String.valueOf(to.minLength()),
                    "MinLength constraint changed from " + from.minLength() + " to " + to.minLength() + " at " + location, false, false);
        }
        if (!Objects.equals(from.maxLength(), to.maxLength())) {
            addChange(changes, DriftChangeType.CONSTRAINT_CHANGED, context, path, method, location + ".maxLength",
                    String.valueOf(from.maxLength()), String.valueOf(to.maxLength()),
                    "MaxLength constraint changed from " + from.maxLength() + " to " + to.maxLength() + " at " + location, false, false);
        }
        if (!Objects.equals(from.pattern(), to.pattern())) {
            addChange(changes, DriftChangeType.CONSTRAINT_CHANGED, context, path, method, location + ".pattern",
                    from.pattern(), to.pattern(),
                    "Pattern regex constraint changed from '" + from.pattern() + "' to '" + to.pattern() + "' at " + location, false, false);
        }
    }

    private void compareObjectProperties(
            String path,
            String method,
            String location,
            NormalizedSchema from,
            NormalizedSchema to,
            DriftClassifier.SchemaContext context,
            List<ContractDriftChange> changes,
            Set<String> visitedComparisons
    ) {
        Map<String, NormalizedSchema> fromProps = from.properties() != null ? new TreeMap<>(from.properties()) : Collections.emptyMap();
        Map<String, NormalizedSchema> toProps = to.properties() != null ? new TreeMap<>(to.properties()) : Collections.emptyMap();

        Set<String> fromReq = from.requiredProperties() != null ? new HashSet<>(from.requiredProperties()) : Collections.emptySet();
        Set<String> toReq = to.requiredProperties() != null ? new HashSet<>(to.requiredProperties()) : Collections.emptySet();

        // Check removed properties
        for (Map.Entry<String, NormalizedSchema> entry : fromProps.entrySet()) {
            String propName = entry.getKey();
            NormalizedSchema propFromSchema = entry.getValue();
            boolean wasRequired = fromReq.contains(propName);

            if (!toProps.containsKey(propName)) {
                addChange(changes, DriftChangeType.PROPERTY_REMOVED, context, path, method, location + "." + propName,
                        propName + " (required=" + wasRequired + ")", null,
                        "Property '" + propName + "' removed from " + location, false, wasRequired);
            } else {
                NormalizedSchema propToSchema = toProps.get(propName);
                boolean isRequired = toReq.contains(propName);

                if (wasRequired != isRequired) {
                    addChange(changes, DriftChangeType.PROPERTY_REQUIRED_CHANGED, context, path, method, location + "." + propName + ".required",
                            String.valueOf(wasRequired), String.valueOf(isRequired),
                            "Property '" + propName + "' required changed from " + wasRequired + " to " + isRequired + " at " + location,
                            isRequired, wasRequired);
                }

                compareSchemas(path, method, location + "." + propName, propFromSchema, propToSchema,
                        context, changes, visitedComparisons);
            }
        }

        // Check added properties
        for (Map.Entry<String, NormalizedSchema> entry : toProps.entrySet()) {
            String propName = entry.getKey();
            boolean isRequired = toReq.contains(propName);

            if (!fromProps.containsKey(propName)) {
                addChange(changes, DriftChangeType.PROPERTY_ADDED, context, path, method, location + "." + propName,
                        null, propName + " (required=" + isRequired + ")",
                        "Property '" + propName + "' added at " + location + " (required=" + isRequired + ")",
                        isRequired, false);
            }
        }
    }

    private void addChange(
            List<ContractDriftChange> changes,
            DriftChangeType changeType,
            DriftClassifier.SchemaContext context,
            String path,
            String method,
            String location,
            String oldValue,
            String newValue,
            String message,
            boolean isRequired,
            boolean wasRequired
    ) {
        DriftClassifier.ClassificationResult res = classifier.classify(changeType, context, isRequired, wasRequired);
        ContractDriftChange change = new ContractDriftChange(
                changeType,
                res.classification(),
                res.severity(),
                path,
                method,
                location,
                oldValue,
                newValue,
                message
        );
        changes.add(change);
    }

    private Map<String, NormalizedEndpoint> toNormalizedEndpointMap(List<NormalizedEndpoint> endpoints) {
        if (endpoints == null) return Collections.emptyMap();
        Map<String, NormalizedEndpoint> map = new TreeMap<>();
        for (NormalizedEndpoint ep : endpoints) {
            String method = ep.method() != null ? ep.method().toUpperCase() : "GET";
            String key = method + " " + ep.path();
            map.put(key, ep);
        }
        return map;
    }

    private Map<String, NormalizedParameter> toNormalizedParamMap(List<NormalizedParameter> parameters) {
        if (parameters == null) return Collections.emptyMap();
        Map<String, NormalizedParameter> map = new TreeMap<>();
        for (NormalizedParameter p : parameters) {
            String key = p.name() + ":" + (p.location() != null ? p.location().name() : "QUERY");
            map.put(key, p);
        }
        return map;
    }

    private Map<String, NormalizedResponse> toNormalizedResponseMap(List<NormalizedResponse> responses) {
        if (responses == null) return Collections.emptyMap();
        Map<String, NormalizedResponse> map = new TreeMap<>();
        for (NormalizedResponse r : responses) {
            map.put(r.statusCode(), r);
        }
        return map;
    }
}
