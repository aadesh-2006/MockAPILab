package com.mockapilab;

import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import com.mockapilab.modules.contract.model.normalized.NormalizedParameter;
import com.mockapilab.modules.contract.model.normalized.NormalizedResponse;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.contract.model.normalized.ParameterLocation;
import com.mockapilab.modules.contract.parser.OpenApiContractParser;
import com.mockapilab.modules.contract.validation.ContractValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiContractParserTest {

    private OpenApiContractParser parser;

    @BeforeEach
    void setUp() {
        parser = new OpenApiContractParser();
    }

    private static final String SAMPLE_YAML = """
            openapi: 3.0.3
            info:
              title: Petstore Service
              description: Pet store sample
              version: 1.2.0
            paths:
              /pets:
                get:
                  summary: List all pets
                  operationId: listPets
                  parameters:
                    - name: limit
                      in: query
                      required: false
                      schema:
                        type: integer
                    - name: X-Request-ID
                      in: header
                      required: true
                      schema:
                        type: string
                  responses:
                    '200':
                      description: Array of pets
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              $ref: '#/components/schemas/Pet'
                post:
                  summary: Create pet
                  operationId: createPet
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/PetCreateRequest'
                  responses:
                    '201':
                      description: Pet created
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Pet'
              /pets/{petId}:
                get:
                  summary: Get pet by ID
                  operationId: getPetById
                  parameters:
                    - name: petId
                      in: path
                      required: true
                      schema:
                        type: string
                        format: uuid
                  responses:
                    '200':
                      description: Pet details
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Pet'
            components:
              schemas:
                StatusEnum:
                  type: string
                  enum:
                    - AVAILABLE
                    - PENDING
                    - SOLD
                Pet:
                  type: object
                  required:
                    - id
                    - name
                    - status
                  properties:
                    id:
                      type: string
                      format: uuid
                    name:
                      type: string
                    status:
                      $ref: '#/components/schemas/StatusEnum'
                    details:
                      type: object
                      properties:
                        breed:
                          type: string
                        age:
                          type: integer
                PetCreateRequest:
                  type: object
                  required:
                    - name
                  properties:
                    name:
                      type: string
                    status:
                      $ref: '#/components/schemas/StatusEnum'
            """;

    private static final String SAMPLE_JSON = """
            {
              "openapi": "3.0.1",
              "info": {
                "title": "JSON Orders API",
                "version": "2.0.0"
              },
              "paths": {
                "/orders": {
                  "get": {
                    "summary": "List orders",
                    "responses": {
                      "200": {
                        "description": "OK"
                      }
                    }
                  }
                }
              }
            }
            """;

    @Test
    @DisplayName("1. Valid OpenAPI JSON parses successfully")
    void testParseValidJson() {
        NormalizedContract contract = parser.parse(SAMPLE_JSON);
        assertThat(contract).isNotNull();
        assertThat(contract.metadata().title()).isEqualTo("JSON Orders API");
        assertThat(contract.metadata().version()).isEqualTo("2.0.0");
        assertThat(contract.endpoints()).hasSize(1);
        assertThat(contract.endpoints().get(0).path()).isEqualTo("/orders");
        assertThat(contract.endpoints().get(0).method()).isEqualTo("GET");
    }

    @Test
    @DisplayName("2. Valid OpenAPI YAML parses successfully")
    void testParseValidYaml() {
        NormalizedContract contract = parser.parse(SAMPLE_YAML);
        assertThat(contract).isNotNull();
        assertThat(contract.metadata().title()).isEqualTo("Petstore Service");
        assertThat(contract.metadata().version()).isEqualTo("1.2.0");
    }

    @Test
    @DisplayName("3. Multiple endpoints are extracted")
    void testMultipleEndpointsExtracted() {
        NormalizedContract contract = parser.parse(SAMPLE_YAML);
        assertThat(contract.endpoints()).hasSize(3); // GET /pets, POST /pets, GET /pets/{petId}
        List<String> paths = contract.endpoints().stream().map(NormalizedEndpoint::path).toList();
        assertThat(paths).contains("/pets", "/pets/{petId}");
    }

    @Test
    @DisplayName("4. Path, Query, and Header parameters are extracted")
    void testParametersExtracted() {
        NormalizedContract contract = parser.parse(SAMPLE_YAML);

        NormalizedEndpoint getPets = contract.endpoints().stream()
                .filter(e -> e.path().equals("/pets") && e.method().equals("GET"))
                .findFirst().orElseThrow();

        assertThat(getPets.parameters()).hasSize(2);

        NormalizedParameter queryParam = getPets.parameters().stream()
                .filter(p -> p.location() == ParameterLocation.QUERY)
                .findFirst().orElseThrow();
        assertThat(queryParam.name()).isEqualTo("limit");
        assertThat(queryParam.schema().type()).isEqualTo("integer");

        NormalizedParameter headerParam = getPets.parameters().stream()
                .filter(p -> p.location() == ParameterLocation.HEADER)
                .findFirst().orElseThrow();
        assertThat(headerParam.name()).isEqualTo("X-Request-ID");
        assertThat(headerParam.required()).isTrue();

        NormalizedEndpoint getPetById = contract.endpoints().stream()
                .filter(e -> e.path().equals("/pets/{petId}"))
                .findFirst().orElseThrow();

        NormalizedParameter pathParam = getPetById.parameters().stream()
                .filter(p -> p.location() == ParameterLocation.PATH)
                .findFirst().orElseThrow();
        assertThat(pathParam.name()).isEqualTo("petId");
        assertThat(pathParam.required()).isTrue();
        assertThat(pathParam.schema().format()).isEqualTo("uuid");
    }

    @Test
    @DisplayName("5. Request body is extracted")
    void testRequestBodyExtracted() {
        NormalizedContract contract = parser.parse(SAMPLE_YAML);

        NormalizedEndpoint postPet = contract.endpoints().stream()
                .filter(e -> e.path().equals("/pets") && e.method().equals("POST"))
                .findFirst().orElseThrow();

        assertThat(postPet.requestBody()).isNotNull();
        assertThat(postPet.requestBody().required()).isTrue();
        assertThat(postPet.requestBody().contentTypes()).containsKey("application/json");
        assertThat(postPet.requestBody().contentTypes().get("application/json").schema().ref()).isEqualTo("PetCreateRequest");
    }

    @Test
    @DisplayName("6. Responses are extracted")
    void testResponsesExtracted() {
        NormalizedContract contract = parser.parse(SAMPLE_YAML);

        NormalizedEndpoint getPets = contract.endpoints().stream()
                .filter(e -> e.path().equals("/pets") && e.method().equals("GET"))
                .findFirst().orElseThrow();

        assertThat(getPets.responses()).hasSize(1);
        NormalizedResponse resp = getPets.responses().get(0);
        assertThat(resp.statusCode()).isEqualTo("200");
        assertThat(resp.contentTypes()).containsKey("application/json");
        assertThat(resp.contentTypes().get("application/json").schema().type()).isEqualTo("array");
    }

    @Test
    @DisplayName("7. Nested object schemas are normalized")
    void testNestedObjectSchemaNormalized() {
        NormalizedContract contract = parser.parse(SAMPLE_YAML);

        assertThat(contract.schemas()).containsKey("Pet");
        NormalizedSchema petSchema = contract.schemas().get("Pet");
        assertThat(petSchema.type()).isEqualTo("object");
        assertThat(petSchema.requiredProperties()).contains("id", "name", "status");
        assertThat(petSchema.properties()).containsKey("details");

        NormalizedSchema detailsSchema = petSchema.properties().get("details");
        assertThat(detailsSchema.type()).isEqualTo("object");
        assertThat(detailsSchema.properties()).containsKey("breed");
        assertThat(detailsSchema.properties()).containsKey("age");
    }

    @Test
    @DisplayName("8. Arrays are normalized")
    void testArraysNormalized() {
        NormalizedContract contract = parser.parse(SAMPLE_YAML);

        NormalizedEndpoint getPets = contract.endpoints().stream()
                .filter(e -> e.path().equals("/pets") && e.method().equals("GET"))
                .findFirst().orElseThrow();

        NormalizedSchema responseSchema = getPets.responses().get(0).contentTypes().get("application/json").schema();
        assertThat(responseSchema.type()).isEqualTo("array");
        assertThat(responseSchema.items()).isNotNull();
        assertThat(responseSchema.items().ref()).isEqualTo("Pet");
    }

    @Test
    @DisplayName("9. Enums are normalized")
    void testEnumsNormalized() {
        NormalizedContract contract = parser.parse(SAMPLE_YAML);

        assertThat(contract.schemas()).containsKey("StatusEnum");
        NormalizedSchema enumSchema = contract.schemas().get("StatusEnum");
        assertThat(enumSchema.type()).isEqualTo("string");
        assertThat(enumSchema.enumConstants()).containsExactly("AVAILABLE", "PENDING", "SOLD");
    }

    @Test
    @DisplayName("10. Local $ref references resolve")
    void testLocalRefResolution() {
        NormalizedContract contract = parser.parse(SAMPLE_YAML);

        assertThat(contract.schemas()).containsKey("Pet");
        NormalizedSchema statusProp = contract.schemas().get("Pet").properties().get("status");
        assertThat(statusProp.ref()).isEqualTo("StatusEnum");
    }

    @Test
    @DisplayName("11. Malformed OpenAPI rejected")
    void testMalformedOpenApiRejected() {
        String malformed = "{ this is completely invalid json ";
        assertThatThrownBy(() -> parser.parse(malformed))
                .isInstanceOf(ContractValidationException.class);
    }

    @Test
    @DisplayName("12. Unsupported Swagger 2.0 version rejected")
    void testUnsupportedVersionRejected() {
        String swagger2 = """
                swagger: "2.0"
                info:
                  title: Old API
                  version: 1.0
                paths: {}
                """;
        assertThatThrownBy(() -> parser.parse(swagger2))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("OpenAPI 3.x");
    }

    @Test
    @DisplayName("13. Unresolved $ref reference rejected")
    void testUnresolvedReferenceRejected() {
        String brokenRef = """
                openapi: 3.0.3
                info:
                  title: Broken API
                  version: 1.0.0
                paths:
                  /items:
                    get:
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/NonExistentSchema'
                components:
                  schemas:
                    OtherSchema:
                      type: string
                """;
        assertThatThrownBy(() -> parser.parse(brokenRef))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("Unresolved reference");
    }

    @Test
    @DisplayName("14. Sample Users API YAML from docs/examples parses cleanly with all endpoints and schemas")
    void testParseSampleUsersApiYaml() throws Exception {
        java.nio.file.Path samplePath = java.nio.file.Paths.get("..", "docs", "examples", "sample-users-api.yaml");
        if (java.nio.file.Files.exists(samplePath)) {
            String content = java.nio.file.Files.readString(samplePath);
            NormalizedContract contract = parser.parse(content);
            assertThat(contract).isNotNull();
            assertThat(contract.metadata().title()).isEqualTo("Users Management Service API");
            assertThat(contract.endpoints()).hasSize(4); // GET /users, POST /users, GET /users/{id}, DELETE /users/{id}
            assertThat(contract.schemas()).containsKeys("User", "UserCreateRequest", "ErrorResponse", "RoleEnum");
        }
    }
}
