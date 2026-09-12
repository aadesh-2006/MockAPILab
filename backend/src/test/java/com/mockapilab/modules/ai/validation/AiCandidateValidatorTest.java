package com.mockapilab.modules.ai.validation;

import com.mockapilab.modules.ai.model.candidate.AiCandidateContract;
import com.mockapilab.modules.ai.model.candidate.AiCandidateEndpoint;
import com.mockapilab.modules.ai.model.candidate.AiCandidateParameter;
import com.mockapilab.modules.ai.model.candidate.AiCandidateRequestBody;
import com.mockapilab.modules.ai.model.candidate.AiCandidateResponse;
import com.mockapilab.modules.ai.model.candidate.AiCandidateSchema;
import com.mockapilab.modules.contract.validation.ContractValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AI Candidate Validator Tests")
class AiCandidateValidatorTest {

    private AiCandidateValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AiCandidateValidator();
    }

    @Test
    @DisplayName("Valid candidate contract passes validation")
    void validate_validCandidate_passes() {
        AiCandidateSchema userSchema = new AiCandidateSchema(
                "object", null, "User entity", false, null, null, null,
                Map.of(
                        "id", new AiCandidateSchema("string", "uuid", null, false, null, null, null, null, null, null, null, null, null, null, null, null),
                        "name", new AiCandidateSchema("string", null, null, false, null, null, null, null, null, null, null, null, null, null, null, null),
                        "role", new AiCandidateSchema("string", null, null, false, null, null, List.of("ADMIN", "USER"), null, null, null, null, null, null, null, null, null)
                ),
                List.of("id", "name"),
                null, null, null, null, null, null, null
        );

        AiCandidateEndpoint getEndpoint = new AiCandidateEndpoint(
                "/users/{id}",
                "GET",
                "getUserById",
                "Get user by ID",
                "Retrieves a single user",
                List.of(new AiCandidateParameter("id", "path", true, "User ID", new AiCandidateSchema("string", "uuid", null, false, null, null, null, null, null, null, null, null, null, null, null, null))),
                null,
                List.of(new AiCandidateResponse("200", "User found", "application/json", new AiCandidateSchema(null, null, null, false, null, null, null, null, null, null, "#/components/schemas/User", null, null, null, null, null)))
        );

        AiCandidateContract contract = new AiCandidateContract(
                "User Service",
                "Service managing users",
                "1.0.0",
                List.of(getEndpoint),
                Map.of("User", userSchema)
        );

        assertThatCode(() -> validator.validate(contract)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Throws ContractValidationException when candidate contract is null or empty")
    void validate_nullOrEmpty_throwsException() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("AI extraction failed");

        AiCandidateContract emptyContract = new AiCandidateContract("Title", null, "1.0.0", List.of(), Map.of());
        assertThatThrownBy(() -> validator.validate(emptyContract))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("at least one endpoint");
    }

    @Test
    @DisplayName("Throws ContractValidationException on missing leading slash in path")
    void validate_missingLeadingSlash_throwsException() {
        AiCandidateEndpoint endpoint = new AiCandidateEndpoint(
                "users",
                "GET",
                null, null, null,
                null, null,
                List.of(new AiCandidateResponse("200", "OK", "application/json", null))
        );

        AiCandidateContract contract = new AiCandidateContract("Title", null, "1.0.0", List.of(endpoint), Map.of());
        assertThatThrownBy(() -> validator.validate(contract))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("must start with a leading slash");
    }

    @Test
    @DisplayName("Throws ContractValidationException on unsupported HTTP method")
    void validate_unsupportedHttpMethod_throwsException() {
        AiCandidateEndpoint endpoint = new AiCandidateEndpoint(
                "/users",
                "CONNECT",
                null, null, null,
                null, null,
                List.of(new AiCandidateResponse("200", "OK", "application/json", null))
        );

        AiCandidateContract contract = new AiCandidateContract("Title", null, "1.0.0", List.of(endpoint), Map.of());
        assertThatThrownBy(() -> validator.validate(contract))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("Unsupported HTTP method");
    }

    @Test
    @DisplayName("Throws ContractValidationException on duplicate endpoint definitions")
    void validate_duplicateEndpoint_throwsException() {
        AiCandidateEndpoint ep1 = new AiCandidateEndpoint("/users", "GET", null, null, null, null, null, List.of(new AiCandidateResponse("200", "OK", "application/json", null)));
        AiCandidateEndpoint ep2 = new AiCandidateEndpoint("/users", "GET", null, null, null, null, null, List.of(new AiCandidateResponse("200", "OK", "application/json", null)));

        AiCandidateContract contract = new AiCandidateContract("Title", null, "1.0.0", List.of(ep1, ep2), Map.of());
        assertThatThrownBy(() -> validator.validate(contract))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("Duplicate endpoint");
    }

    @Test
    @DisplayName("Throws ContractValidationException on invalid path parameter")
    void validate_invalidPathParameter_throwsException() {
        AiCandidateEndpoint endpoint = new AiCandidateEndpoint(
                "/users",
                "GET",
                null, null, null,
                List.of(new AiCandidateParameter("id", "path", true, "User ID", null)),
                null,
                List.of(new AiCandidateResponse("200", "OK", "application/json", null))
        );

        AiCandidateContract contract = new AiCandidateContract("Title", null, "1.0.0", List.of(endpoint), Map.of());
        assertThatThrownBy(() -> validator.validate(contract))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("missing from path template");
    }

    @Test
    @DisplayName("Throws ContractValidationException on unresolved schema ref")
    void validate_unresolvedSchemaRef_throwsException() {
        AiCandidateEndpoint endpoint = new AiCandidateEndpoint(
                "/users",
                "GET",
                null, null, null,
                null, null,
                List.of(new AiCandidateResponse("200", "OK", "application/json", new AiCandidateSchema(null, null, null, false, null, null, null, null, null, null, "#/components/schemas/NonExistentModel", null, null, null, null, null)))
        );

        AiCandidateContract contract = new AiCandidateContract("Title", null, "1.0.0", List.of(endpoint), Map.of());
        assertThatThrownBy(() -> validator.validate(contract))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("references undefined schema");
    }

    @Test
    @DisplayName("Throws ContractValidationException on required property missing from declared properties")
    void validate_missingRequiredProperty_throwsException() {
        AiCandidateSchema schema = new AiCandidateSchema(
                "object", null, "Model", false, null, null, null,
                Map.of("name", new AiCandidateSchema("string", null, null, false, null, null, null, null, null, null, null, null, null, null, null, null)),
                List.of("name", "age"),
                null, null, null, null, null, null, null
        );

        AiCandidateEndpoint endpoint = new AiCandidateEndpoint(
                "/users", "GET", null, null, null, null, null,
                List.of(new AiCandidateResponse("200", "OK", "application/json", null))
        );

        AiCandidateContract contract = new AiCandidateContract("Title", null, "1.0.0", List.of(endpoint), Map.of("User", schema));
        assertThatThrownBy(() -> validator.validate(contract))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("missing from defined properties");
    }
}