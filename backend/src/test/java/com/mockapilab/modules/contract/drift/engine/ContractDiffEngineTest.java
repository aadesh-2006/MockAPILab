package com.mockapilab.modules.contract.drift.engine;

import com.mockapilab.modules.contract.drift.model.DriftChangeType;
import com.mockapilab.modules.contract.drift.model.DriftClassification;
import com.mockapilab.modules.contract.drift.model.DriftSeverity;
import com.mockapilab.modules.contract.model.normalized.ContractMetadata;
import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import com.mockapilab.modules.contract.model.normalized.NormalizedMediaType;
import com.mockapilab.modules.contract.model.normalized.NormalizedParameter;
import com.mockapilab.modules.contract.model.normalized.NormalizedResponse;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.contract.model.normalized.ParameterLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContractDiffEngineTest {

    private ContractDiffEngine diffEngine;

    @BeforeEach
    void setUp() {
        diffEngine = new ContractDiffEngine(new DriftClassifier());
    }

    @Test
    @DisplayName("Diffing identical contracts returns zero changes and NONE severity")
    void testIdenticalContracts() {
        NormalizedSchema userSchema = new NormalizedSchema("object", null, "User entity", false, null, null, null,
                Map.of("id", NormalizedSchema.string("uuid", "User ID"), "name", NormalizedSchema.string(null, "User Name")),
                List.of("id", "name"), null, null);

        NormalizedEndpoint endpoint = new NormalizedEndpoint("/users", "GET", "listUsers", "List all users", null,
                List.of(new NormalizedParameter("limit", ParameterLocation.QUERY, false, "Page size", NormalizedSchema.integer("int32", "Limit"))),
                null,
                List.of(new NormalizedResponse("200", "OK", Map.of("application/json", new NormalizedMediaType(NormalizedSchema.array(userSchema, "User list"), null)), null))
        );

        NormalizedContract c1 = new NormalizedContract(ContractMetadata.of("User API", "User management", "1.0.0"), List.of(endpoint), Map.of("User", userSchema));
        NormalizedContract c2 = new NormalizedContract(ContractMetadata.of("User API", "User management", "1.0.0"), List.of(endpoint), Map.of("User", userSchema));

        var result = diffEngine.diff(c1, c2);

        assertThat(result.changes()).isEmpty();
        assertThat(result.breakingCount()).isEqualTo(0);
        assertThat(result.nonBreakingCount()).isEqualTo(0);
        assertThat(result.informationalCount()).isEqualTo(0);
        assertThat(result.overallSeverity()).isEqualTo(DriftSeverity.NONE);
    }

    @Test
    @DisplayName("Adding an endpoint produces NON_BREAKING change with LOW severity")
    void testEndpointAdded() {
        NormalizedEndpoint ep1 = new NormalizedEndpoint("/users", "GET", "listUsers", "List users", null, List.of(), null, List.of());
        NormalizedEndpoint ep2 = new NormalizedEndpoint("/users/{id}", "GET", "getUser", "Get user by ID", null, List.of(), null, List.of());

        NormalizedContract c1 = new NormalizedContract(ContractMetadata.of("API", null, "1.0"), List.of(ep1), Map.of());
        NormalizedContract c2 = new NormalizedContract(ContractMetadata.of("API", null, "1.0"), List.of(ep1, ep2), Map.of());

        var result = diffEngine.diff(c1, c2);

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).getChangeType()).isEqualTo(DriftChangeType.ENDPOINT_ADDED);
        assertThat(result.changes().get(0).getClassification()).isEqualTo(DriftClassification.NON_BREAKING);
        assertThat(result.breakingCount()).isEqualTo(0);
        assertThat(result.nonBreakingCount()).isEqualTo(1);
        assertThat(result.overallSeverity()).isEqualTo(DriftSeverity.LOW);
    }

    @Test
    @DisplayName("Removing an endpoint produces BREAKING change with CRITICAL severity")
    void testEndpointRemoved() {
        NormalizedEndpoint ep1 = new NormalizedEndpoint("/users", "GET", "listUsers", "List users", null, List.of(), null, List.of());
        NormalizedEndpoint ep2 = new NormalizedEndpoint("/orders", "POST", "createOrder", "Create order", null, List.of(), null, List.of());

        NormalizedContract c1 = new NormalizedContract(ContractMetadata.of("API", null, "1.0"), List.of(ep1, ep2), Map.of());
        NormalizedContract c2 = new NormalizedContract(ContractMetadata.of("API", null, "1.0"), List.of(ep1), Map.of());

        var result = diffEngine.diff(c1, c2);

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).getChangeType()).isEqualTo(DriftChangeType.ENDPOINT_REMOVED);
        assertThat(result.changes().get(0).getClassification()).isEqualTo(DriftClassification.BREAKING);
        assertThat(result.breakingCount()).isEqualTo(1);
        assertThat(result.overallSeverity()).isEqualTo(DriftSeverity.CRITICAL);
    }

    @Test
    @DisplayName("Adding required parameter is BREAKING, adding optional parameter is NON_BREAKING")
    void testParameterAdded() {
        NormalizedParameter optParam = new NormalizedParameter("filter", ParameterLocation.QUERY, false, "filter", NormalizedSchema.string(null, null));
        NormalizedParameter reqParam = new NormalizedParameter("apiKey", ParameterLocation.HEADER, true, "api key", NormalizedSchema.string(null, null));

        NormalizedEndpoint ep1 = new NormalizedEndpoint("/users", "GET", "listUsers", "List users", null, List.of(), null, List.of());
        NormalizedEndpoint ep2Opt = new NormalizedEndpoint("/users", "GET", "listUsers", "List users", null, List.of(optParam), null, List.of());
        NormalizedEndpoint ep2Req = new NormalizedEndpoint("/users", "GET", "listUsers", "List users", null, List.of(reqParam), null, List.of());

        var resOpt = diffEngine.diff(
                new NormalizedContract(null, List.of(ep1), Map.of()),
                new NormalizedContract(null, List.of(ep2Opt), Map.of())
        );
        assertThat(resOpt.changes()).hasSize(1);
        assertThat(resOpt.changes().get(0).getClassification()).isEqualTo(DriftClassification.NON_BREAKING);

        var resReq = diffEngine.diff(
                new NormalizedContract(null, List.of(ep1), Map.of()),
                new NormalizedContract(null, List.of(ep2Req), Map.of())
        );
        assertThat(resReq.changes()).hasSize(1);
        assertThat(resReq.changes().get(0).getClassification()).isEqualTo(DriftClassification.BREAKING);
    }

    @Test
    @DisplayName("Response schema: removing property is BREAKING, adding property is NON_BREAKING")
    void testResponseSchemaEvolution() {
        NormalizedSchema s1 = new NormalizedSchema("object", null, null, false, null, null, null,
                Map.of("id", NormalizedSchema.string(null, null), "name", NormalizedSchema.string(null, null)),
                List.of("id"), null, null);

        NormalizedSchema s2 = new NormalizedSchema("object", null, null, false, null, null, null,
                Map.of("id", NormalizedSchema.string(null, null), "name", NormalizedSchema.string(null, null), "email", NormalizedSchema.string("email", null)),
                List.of("id"), null, null);

        NormalizedEndpoint ep1 = new NormalizedEndpoint("/users/{id}", "GET", "get", null, null, List.of(), null,
                List.of(new NormalizedResponse("200", "OK", Map.of("application/json", new NormalizedMediaType(s1, null)), null)));

        NormalizedEndpoint ep2 = new NormalizedEndpoint("/users/{id}", "GET", "get", null, null, List.of(), null,
                List.of(new NormalizedResponse("200", "OK", Map.of("application/json", new NormalizedMediaType(s2, null)), null)));

        // v1 -> v2 (added 'email'): NON_BREAKING
        var v1ToV2 = diffEngine.diff(
                new NormalizedContract(null, List.of(ep1), Map.of()),
                new NormalizedContract(null, List.of(ep2), Map.of())
        );
        assertThat(v1ToV2.breakingCount()).isEqualTo(0);
        assertThat(v1ToV2.nonBreakingCount()).isEqualTo(1);
        assertThat(v1ToV2.changes().get(0).getChangeType()).isEqualTo(DriftChangeType.PROPERTY_ADDED);
        assertThat(v1ToV2.changes().get(0).getClassification()).isEqualTo(DriftClassification.NON_BREAKING);

        // v2 -> v1 (removed 'email'): BREAKING
        var v2ToV1 = diffEngine.diff(
                new NormalizedContract(null, List.of(ep2), Map.of()),
                new NormalizedContract(null, List.of(ep1), Map.of())
        );
        assertThat(v2ToV1.breakingCount()).isEqualTo(1);
        assertThat(v2ToV1.changes().get(0).getChangeType()).isEqualTo(DriftChangeType.PROPERTY_REMOVED);
        assertThat(v2ToV1.changes().get(0).getClassification()).isEqualTo(DriftClassification.BREAKING);
    }

    @Test
    @DisplayName("Ordering insensitivity: maps and lists in different order produce 0 diffs")
    void testOrderingInsensitivity() {
        NormalizedSchema s1 = new NormalizedSchema("object", null, null, false, null, null, null,
                Map.of("alpha", NormalizedSchema.string(null, null), "beta", NormalizedSchema.string(null, null)),
                List.of("alpha", "beta"), null, null);

        Map<String, NormalizedSchema> reversedProps = new HashMap<>();
        reversedProps.put("beta", NormalizedSchema.string(null, null));
        reversedProps.put("alpha", NormalizedSchema.string(null, null));

        NormalizedSchema s2 = new NormalizedSchema("object", null, null, false, null, null, null,
                reversedProps,
                List.of("beta", "alpha"), null, null);

        NormalizedEndpoint ep1 = new NormalizedEndpoint("/test", "GET", "test", null, null, List.of(), null,
                List.of(new NormalizedResponse("200", "OK", Map.of("application/json", new NormalizedMediaType(s1, null)), null)));
        NormalizedEndpoint ep2 = new NormalizedEndpoint("/test", "GET", "test", null, null, List.of(), null,
                List.of(new NormalizedResponse("200", "OK", Map.of("application/json", new NormalizedMediaType(s2, null)), null)));

        var res = diffEngine.diff(
                new NormalizedContract(null, List.of(ep1), Map.of()),
                new NormalizedContract(null, List.of(ep2), Map.of())
        );

        assertThat(res.changes()).isEmpty();
        assertThat(res.overallSeverity()).isEqualTo(DriftSeverity.NONE);
    }
}
