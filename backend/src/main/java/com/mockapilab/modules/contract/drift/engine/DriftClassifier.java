package com.mockapilab.modules.contract.drift.engine;

import com.mockapilab.modules.contract.drift.model.DriftChangeType;
import com.mockapilab.modules.contract.drift.model.DriftClassification;
import com.mockapilab.modules.contract.drift.model.DriftSeverity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic classifier that maps schema diff events and contextual semantics
 * to drift classifications (BREAKING, NON_BREAKING, INFORMATIONAL) and severities.
 */
@Component
public class DriftClassifier {

    public enum SchemaContext {
        REQUEST,
        RESPONSE,
        PARAMETER,
        COMPONENT
    }

    public record ClassificationResult(DriftClassification classification, DriftSeverity severity) {}

    public ClassificationResult classify(
            DriftChangeType changeType,
            SchemaContext context,
            boolean isRequired,
            boolean wasRequired
    ) {
        return switch (changeType) {
            case ENDPOINT_ADDED -> new ClassificationResult(DriftClassification.NON_BREAKING, DriftSeverity.LOW);
            case ENDPOINT_REMOVED, METHOD_REMOVED -> new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.CRITICAL);

            case PARAMETER_ADDED -> isRequired
                    ? new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH)
                    : new ClassificationResult(DriftClassification.NON_BREAKING, DriftSeverity.LOW);

            case PARAMETER_REMOVED -> wasRequired
                    ? new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH)
                    : new ClassificationResult(DriftClassification.NON_BREAKING, DriftSeverity.LOW);

            case PARAMETER_TYPE_CHANGED, PARAMETER_LOCATION_CHANGED ->
                    new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH);

            case PARAMETER_REQUIRED_CHANGED -> (!wasRequired && isRequired)
                    ? new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH)
                    : new ClassificationResult(DriftClassification.NON_BREAKING, DriftSeverity.LOW);

            case PARAMETER_ENUM_CHANGED -> new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.MEDIUM);

            case REQUEST_BODY_ADDED -> isRequired
                    ? new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH)
                    : new ClassificationResult(DriftClassification.NON_BREAKING, DriftSeverity.LOW);

            case REQUEST_BODY_REMOVED -> wasRequired
                    ? new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH)
                    : new ClassificationResult(DriftClassification.NON_BREAKING, DriftSeverity.LOW);

            case REQUEST_SCHEMA_CHANGED -> new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH);

            case RESPONSE_STATUS_ADDED -> new ClassificationResult(DriftClassification.NON_BREAKING, DriftSeverity.LOW);
            case RESPONSE_STATUS_REMOVED -> new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH);
            case RESPONSE_SCHEMA_CHANGED -> new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH);

            case PROPERTY_ADDED -> {
                if (context == SchemaContext.REQUEST) {
                    yield isRequired
                            ? new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH)
                            : new ClassificationResult(DriftClassification.NON_BREAKING, DriftSeverity.LOW);
                } else {
                    // Response property addition is backward compatible (additive)
                    yield new ClassificationResult(DriftClassification.NON_BREAKING, DriftSeverity.LOW);
                }
            }

            case PROPERTY_REMOVED -> {
                if (context == SchemaContext.REQUEST) {
                    // Removing a request property is non-breaking if optional, breaking if was required
                    yield wasRequired
                            ? new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH)
                            : new ClassificationResult(DriftClassification.NON_BREAKING, DriftSeverity.LOW);
                } else {
                    // Removing a property from response breaks API consumers expecting it
                    yield new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH);
                }
            }

            case PROPERTY_TYPE_CHANGED -> new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH);

            case PROPERTY_REQUIRED_CHANGED -> {
                if (context == SchemaContext.REQUEST) {
                    yield (!wasRequired && isRequired)
                            ? new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH)
                            : new ClassificationResult(DriftClassification.NON_BREAKING, DriftSeverity.LOW);
                } else {
                    // In response, changing from required to optional means field may now be null/omitted -> BREAKING
                    yield (wasRequired && !isRequired)
                            ? new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.MEDIUM)
                            : new ClassificationResult(DriftClassification.NON_BREAKING, DriftSeverity.LOW);
                }
            }

            case ENUM_VALUE_ADDED -> {
                if (context == SchemaContext.REQUEST) {
                    // Server accepts more values in request -> NON_BREAKING
                    yield new ClassificationResult(DriftClassification.NON_BREAKING, DriftSeverity.LOW);
                } else {
                    // Server produces new enum in response -> can break strict client deserializers
                    yield new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.MEDIUM);
                }
            }

            case ENUM_VALUE_REMOVED -> {
                if (context == SchemaContext.REQUEST) {
                    // Server no longer accepts a value -> BREAKING
                    yield new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.HIGH);
                } else {
                    yield new ClassificationResult(DriftClassification.NON_BREAKING, DriftSeverity.LOW);
                }
            }

            case CONSTRAINT_CHANGED -> new ClassificationResult(DriftClassification.BREAKING, DriftSeverity.MEDIUM);

            case METADATA_CHANGED -> new ClassificationResult(DriftClassification.INFORMATIONAL, DriftSeverity.LOW);
        };
    }

    public DriftSeverity calculateOverallSeverity(
            int breakingCount,
            int nonBreakingCount,
            int informationalCount,
            List<DriftChangeType> changeTypes
    ) {
        if (breakingCount > 0) {
            boolean hasCriticalChange = changeTypes.contains(DriftChangeType.ENDPOINT_REMOVED)
                    || changeTypes.contains(DriftChangeType.METHOD_REMOVED)
                    || changeTypes.contains(DriftChangeType.REQUEST_BODY_REMOVED)
                    || breakingCount >= 3;
            return hasCriticalChange ? DriftSeverity.CRITICAL : DriftSeverity.HIGH;
        }

        if (nonBreakingCount > 0) {
            return nonBreakingCount >= 3 ? DriftSeverity.MEDIUM : DriftSeverity.LOW;
        }

        if (informationalCount > 0) {
            return DriftSeverity.LOW;
        }

        return DriftSeverity.NONE;
    }
}
