package com.mockapilab.modules.contract.drift.engine;

import com.mockapilab.modules.contract.drift.model.DriftChangeType;
import com.mockapilab.modules.contract.drift.model.DriftClassification;
import com.mockapilab.modules.contract.drift.model.DriftSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DriftClassifierTest {

    private DriftClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new DriftClassifier();
    }

    @Test
    @DisplayName("Endpoint added is non-breaking with low severity")
    void testEndpointAdded() {
        var res = classifier.classify(DriftChangeType.ENDPOINT_ADDED, DriftClassifier.SchemaContext.COMPONENT, false, false);
        assertThat(res.classification()).isEqualTo(DriftClassification.NON_BREAKING);
        assertThat(res.severity()).isEqualTo(DriftSeverity.LOW);
    }

    @Test
    @DisplayName("Endpoint removed is breaking with critical severity")
    void testEndpointRemoved() {
        var res = classifier.classify(DriftChangeType.ENDPOINT_REMOVED, DriftClassifier.SchemaContext.COMPONENT, false, false);
        assertThat(res.classification()).isEqualTo(DriftClassification.BREAKING);
        assertThat(res.severity()).isEqualTo(DriftSeverity.CRITICAL);
    }

    @Test
    @DisplayName("Parameter added: required is breaking HIGH, optional is non-breaking LOW")
    void testParameterAdded() {
        var req = classifier.classify(DriftChangeType.PARAMETER_ADDED, DriftClassifier.SchemaContext.PARAMETER, true, false);
        assertThat(req.classification()).isEqualTo(DriftClassification.BREAKING);
        assertThat(req.severity()).isEqualTo(DriftSeverity.HIGH);

        var opt = classifier.classify(DriftChangeType.PARAMETER_ADDED, DriftClassifier.SchemaContext.PARAMETER, false, false);
        assertThat(opt.classification()).isEqualTo(DriftClassification.NON_BREAKING);
        assertThat(opt.severity()).isEqualTo(DriftSeverity.LOW);
    }

    @Test
    @DisplayName("Response property removed is breaking with high severity")
    void testResponsePropertyRemoved() {
        var res = classifier.classify(DriftChangeType.PROPERTY_REMOVED, DriftClassifier.SchemaContext.RESPONSE, false, true);
        assertThat(res.classification()).isEqualTo(DriftClassification.BREAKING);
        assertThat(res.severity()).isEqualTo(DriftSeverity.HIGH);
    }

    @Test
    @DisplayName("Response property added is non-breaking with low severity")
    void testResponsePropertyAdded() {
        var res = classifier.classify(DriftChangeType.PROPERTY_ADDED, DriftClassifier.SchemaContext.RESPONSE, false, false);
        assertThat(res.classification()).isEqualTo(DriftClassification.NON_BREAKING);
        assertThat(res.severity()).isEqualTo(DriftSeverity.LOW);
    }

    @Test
    @DisplayName("Overall severity calculation")
    void testOverallSeverityCalculation() {
        // No changes
        assertThat(classifier.calculateOverallSeverity(0, 0, 0, List.of())).isEqualTo(DriftSeverity.NONE);

        // Only informational
        assertThat(classifier.calculateOverallSeverity(0, 0, 1, List.of(DriftChangeType.METADATA_CHANGED))).isEqualTo(DriftSeverity.LOW);

        // Non-breaking changes (< 3)
        assertThat(classifier.calculateOverallSeverity(0, 2, 0, List.of(DriftChangeType.ENDPOINT_ADDED))).isEqualTo(DriftSeverity.LOW);

        // Non-breaking changes (>= 3)
        assertThat(classifier.calculateOverallSeverity(0, 3, 0, List.of(DriftChangeType.ENDPOINT_ADDED))).isEqualTo(DriftSeverity.MEDIUM);

        // Breaking changes (< 3 and no critical removals)
        assertThat(classifier.calculateOverallSeverity(1, 0, 0, List.of(DriftChangeType.PARAMETER_REQUIRED_CHANGED))).isEqualTo(DriftSeverity.HIGH);

        // Breaking changes with endpoint removal
        assertThat(classifier.calculateOverallSeverity(1, 0, 0, List.of(DriftChangeType.ENDPOINT_REMOVED))).isEqualTo(DriftSeverity.CRITICAL);

        // Breaking changes (>= 3)
        assertThat(classifier.calculateOverallSeverity(3, 0, 0, List.of(DriftChangeType.PROPERTY_TYPE_CHANGED))).isEqualTo(DriftSeverity.CRITICAL);
    }
}
