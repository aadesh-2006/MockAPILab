package com.mockapilab.modules.ai.converter;

import com.mockapilab.modules.ai.model.candidate.AiCandidateContract;
import com.mockapilab.modules.ai.model.candidate.AiCandidateEndpoint;
import com.mockapilab.modules.ai.model.candidate.AiCandidateParameter;
import com.mockapilab.modules.ai.model.candidate.AiCandidateRequestBody;
import com.mockapilab.modules.ai.model.candidate.AiCandidateResponse;
import com.mockapilab.modules.ai.model.candidate.AiCandidateSchema;
import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.contract.model.normalized.ParameterLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI Candidate Converter Tests")
class AiCandidateConverterTest {

    private AiCandidateConverter converter;

    @BeforeEach
    void setUp() {
        converter = new AiCandidateConverter();
    }

    @Test
    @DisplayName("Deterministically converts AiCandidateContract to canonical NormalizedContract")
    void convert_validCandidate_convertsSuccessfully() {
        AiCandidateSchema userSchema = new AiCandidateSchema(
                "object", null, "User Model", false, null, null, null,
                Map.of(
                        "id", new AiCandidateSchema("string", "uuid", "User ID", false, null, null, null, null, null, null, null, null, null, null, null, null),
                        "name", new AiCandidateSchema("string", null, "Full Name", false, null, null, null, null, null, null, null, null, null, null, null, null),
                        "role", new AiCandidateSchema("string", null, "User Role", false, null, null, List.of("ADMIN", "USER"), null, null, null, null, null, null, null, null, null)
                ),
                List.of("id", "name"),
                null, null, null, null, null, null, null
        );

        AiCandidateEndpoint listUsers = new AiCandidateEndpoint(
                "/users",
                "GET",
                "listUsers",
                "List all users",
                "Returns an array of users",
                List.of(new AiCandidateParameter("limit", "query", false, "Max users", new AiCandidateSchema("integer", "int32", null, false, null, null, null, null, null, null, null, null, null, null, null, null))),
                null,
                List.of(new AiCandidateResponse("200", "OK", "application/json", new AiCandidateSchema("array", null, null, false, null, null, null, null, null, new AiCandidateSchema(null, null, null, false, null, null, null, null, null, null, "User", null, null, null, null, null), null, null, null, null, null, null)))
        );

        AiCandidateEndpoint createUser = new AiCandidateEndpoint(
                "/users",
                "POST",
                "createUser",
                "Create a user",
                "Persists a new user",
                null,
                new AiCandidateRequestBody("User payload", true, "application/json", new AiCandidateSchema(null, null, null, false, null, null, null, null, null, null, "User", null, null, null, null, null)),
                List.of(new AiCandidateResponse("201", "Created", "application/json", new AiCandidateSchema(null, null, null, false, null, null, null, null, null, null, "User", null, null, null, null, null)))
        );

        AiCandidateContract candidate = new AiCandidateContract(
                "Users API",
                "User management API",
                "1.0.0",
                List.of(listUsers, createUser),
                Map.of("User", userSchema)
        );

        NormalizedContract normalized = converter.convert(candidate);

        assertThat(normalized).isNotNull();
        assertThat(normalized.metadata().title()).isEqualTo("Users API");
        assertThat(normalized.metadata().description()).isEqualTo("User management API");
        assertThat(normalized.metadata().version()).isEqualTo("1.0.0");

        assertThat(normalized.endpoints()).hasSize(2);
        NormalizedEndpoint ep1 = normalized.endpoints().get(0);
        assertThat(ep1.path()).isEqualTo("/users");
        assertThat(ep1.method()).isEqualTo("GET");
        assertThat(ep1.parameters()).hasSize(1);
        assertThat(ep1.parameters().get(0).name()).isEqualTo("limit");
        assertThat(ep1.parameters().get(0).location()).isEqualTo(ParameterLocation.QUERY);
        assertThat(ep1.parameters().get(0).required()).isFalse();

        NormalizedEndpoint ep2 = normalized.endpoints().get(1);
        assertThat(ep2.path()).isEqualTo("/users");
        assertThat(ep2.method()).isEqualTo("POST");
        assertThat(ep2.requestBody()).isNotNull();
        assertThat(ep2.requestBody().required()).isTrue();
        assertThat(ep2.requestBody().contentTypes().get("application/json").schema().ref()).isEqualTo("User");

        assertThat(normalized.schemas()).containsKey("User");
        NormalizedSchema normalizedUser = normalized.schemas().get("User");
        assertThat(normalizedUser.type()).isEqualTo("object");
        assertThat(normalizedUser.properties()).containsKeys("id", "name", "role");
        assertThat(normalizedUser.requiredProperties()).containsExactlyInAnyOrder("id", "name");
        assertThat(normalizedUser.properties().get("role").enumConstants()).containsExactly("ADMIN", "USER");
    }
}