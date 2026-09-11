package com.mockapilab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.auth.dto.RegisterRequest;
import com.mockapilab.modules.auth.repository.UserRepository;
import com.mockapilab.modules.contract.dto.IngestContractRequest;
import com.mockapilab.modules.contract.model.ContractSourceType;
import com.mockapilab.modules.contract.repository.ContractRepository;
import com.mockapilab.modules.contract.repository.ContractVersionRepository;
import com.mockapilab.modules.project.dto.CreateProjectRequest;
import com.mockapilab.modules.project.repository.ProjectRepository;
import com.mockapilab.modules.runtime.dto.StartRuntimeRequest;
import com.mockapilab.modules.runtime.model.MockRuntimeStatus;
import com.mockapilab.modules.runtime.repository.MockRuntimeRepository;
import com.mockapilab.modules.runtime.state.RuntimeStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MockRuntimeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private ContractVersionRepository contractVersionRepository;

    @Autowired
    private MockRuntimeRepository runtimeRepository;

    @Autowired
    private RuntimeStateStore stateStore;

    @BeforeEach
    void setUp() {
        runtimeRepository.deleteAll();
        contractVersionRepository.deleteAll();
        contractRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
    }

    private static final String PET_STORE_OPENAPI = """
            openapi: 3.0.3
            info:
              title: Petstore Mock API
              description: Stateful pet store contract
              version: 1.0.0
            paths:
              /pets:
                get:
                  summary: List all pets
                  operationId: listPets
                  responses:
                    '200':
                      description: List of pets
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              $ref: '#/components/schemas/Pet'
                post:
                  summary: Create a pet
                  operationId: createPet
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/PetInput'
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
                  responses:
                    '200':
                      description: Pet details
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Pet'
                    '404':
                      description: Pet not found
                put:
                  summary: Update pet by ID
                  operationId: updatePet
                  parameters:
                    - name: petId
                      in: path
                      required: true
                      schema:
                        type: string
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/PetInput'
                  responses:
                    '200':
                      description: Pet updated
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Pet'
                delete:
                  summary: Delete pet
                  operationId: deletePet
                  parameters:
                    - name: petId
                      in: path
                      required: true
                      schema:
                        type: string
                  responses:
                    '204':
                      description: Pet deleted
            components:
              schemas:
                PetInput:
                  type: object
                  required:
                    - name
                    - tag
                  properties:
                    name:
                      type: string
                    tag:
                      type: string
                    status:
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
                  properties:
                    id:
                      type: string
                    name:
                      type: string
                    tag:
                      type: string
                    status:
                      type: string
            """;

    private String registerAndGetToken(String email, String password, String name) throws Exception {
        RegisterRequest req = new RegisterRequest(email, password, name);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode rootNode = objectMapper.readTree(result.getResponse().getContentAsString());
        return rootNode.path("data").path("token").asText();
    }

    private String createProject(String token, String projectName) throws Exception {
        CreateProjectRequest req = new CreateProjectRequest(projectName, "Project for mock runtime tests");
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode rootNode = objectMapper.readTree(result.getResponse().getContentAsString());
        return rootNode.path("data").path("id").asText();
    }

    private String ingestContract(String token, String projectId, String name, String spec) throws Exception {
        IngestContractRequest req = new IngestContractRequest(name, "Mock contract", spec, ContractSourceType.OPENAPI);
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode rootNode = objectMapper.readTree(result.getResponse().getContentAsString());
        return rootNode.path("data").path("id").asText();
    }

    private String startRuntime(String token, String projectId, String contractId, int versionNumber) throws Exception {
        StartRuntimeRequest req = new StartRuntimeRequest("PetStore Dynamic Runtime");
        MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts/" + contractId + "/versions/" + versionNumber + "/runtime")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.status", is("RUNNING")))
                .andExpect(jsonPath("$.data.mockBaseUrl", notNullValue()))
                .andReturn();

        JsonNode rootNode = objectMapper.readTree(result.getResponse().getContentAsString());
        return rootNode.path("data").path("id").asText();
    }

    @Test
    @DisplayName("25. Start runtime returns RUNNING status and mockBaseUrl")
    void testStartRuntimeSuccessfully() throws Exception {
        String token = registerAndGetToken("alice@runtime.com", "Password123!", "Alice");
        String projectId = createProject(token, "Runtime Project");
        String contractId = ingestContract(token, projectId, "Pet Contract", PET_STORE_OPENAPI);

        String runtimeId = startRuntime(token, projectId, contractId, 1);

        // Verify runtime in DB
        UUID rId = UUID.fromString(runtimeId);
        assertThat(runtimeRepository.existsById(rId)).isTrue();
        var runtime = runtimeRepository.findById(rId).orElseThrow();
        assertThat(runtime.getStatus()).isEqualTo(MockRuntimeStatus.RUNNING);
        assertThat(runtime.getStartedAt()).isNotNull();

        // Get Runtime Details via management API
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.id", is(runtimeId)))
                .andExpect(jsonPath("$.data.status", is("RUNNING")))
                .andExpect(jsonPath("$.data.mockBaseUrl", is("/mock/" + runtimeId)))
                .andExpect(jsonPath("$.data.endpointsCount", is(5)));
    }

    @Test
    @DisplayName("26, 27, 28, 29. Full stateful mock lifecycle: POST creates, GET returns, PUT updates, DELETE removes, GET by id returns 404")
    void testStatefulMockCrudOperations() throws Exception {
        String token = registerAndGetToken("bob@runtime.com", "Password123!", "Bob");
        String projectId = createProject(token, "Pet Backend");
        String contractId = ingestContract(token, projectId, "Pet Contract", PET_STORE_OPENAPI);
        String runtimeId = startRuntime(token, projectId, contractId, 1);

        String mockUrl = "/mock/" + runtimeId + "/pets";

        // 1. POST /pets (Public, NO AUTH header required)
        Map<String, Object> newPet = Map.of(
                "name", "Fido",
                "tag", "dog",
                "status", "AVAILABLE"
        );

        MvcResult postResult = mockMvc.perform(post(mockUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newPet)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name", is("Fido")))
                .andExpect(jsonPath("$.tag", is("dog")))
                .andExpect(jsonPath("$.status", is("AVAILABLE")))
                .andReturn();

        String petId = objectMapper.readTree(postResult.getResponse().getContentAsString()).path("id").asText();

        // 2. GET /pets -> list contains our created pet
        mockMvc.perform(get(mockUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[?(@.id == '" + petId + "')].name").value("Fido"));

        // 3. GET /pets/{petId} -> returns exact pet
        mockMvc.perform(get(mockUrl + "/" + petId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(petId)))
                .andExpect(jsonPath("$.name", is("Fido")))
                .andExpect(jsonPath("$.tag", is("dog")));

        // 4. PUT /pets/{petId} -> update pet status to SOLD
        Map<String, Object> updatePet = Map.of(
                "name", "Fido",
                "tag", "dog",
                "status", "SOLD"
        );

        mockMvc.perform(put(mockUrl + "/" + petId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatePet)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(petId)))
                .andExpect(jsonPath("$.status", is("SOLD")));

        // 5. Verify update persisted
        mockMvc.perform(get(mockUrl + "/" + petId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SOLD")));

        // 6. DELETE /pets/{petId} -> returns 204 or 200
        mockMvc.perform(delete(mockUrl + "/" + petId))
                .andExpect(status().isNoContent());

        // 7. GET /pets/{petId} -> returns 404 NOT FOUND
        mockMvc.perform(get(mockUrl + "/" + petId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("not found")));
    }

    @Test
    @DisplayName("30. Request validation: missing required fields or invalid types rejected with 400 Bad Request")
    void testRequestValidationEnforcement() throws Exception {
        String token = registerAndGetToken("validator@runtime.com", "Password123!", "Val");
        String projectId = createProject(token, "Val Project");
        String contractId = ingestContract(token, projectId, "Pet Contract", PET_STORE_OPENAPI);
        String runtimeId = startRuntime(token, projectId, contractId, 1);

        String mockUrl = "/mock/" + runtimeId + "/pets";

        // Missing required field 'tag'
        Map<String, Object> invalidPet = Map.of(
                "name", "Incomplete Pet"
        );

        mockMvc.perform(post(mockUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidPet)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Request validation failed")))
                .andExpect(jsonPath("$.details", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.details[0]", containsString("Missing required property: 'tag'")));

        // Invalid enum value for 'status'
        Map<String, Object> invalidEnumPet = Map.of(
                "name", "Pet With Bad Enum",
                "tag", "dog",
                "status", "SUPER_UNKNOWN"
        );

        mockMvc.perform(post(mockUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidEnumPet)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Request validation failed")))
                .andExpect(jsonPath("$.details[0]", containsString("is not one of allowed enum values")));
    }

    @Test
    @DisplayName("31. Runtime Isolation: Two runtimes on the same contract maintain isolated in-memory state")
    void testRuntimeStateIsolation() throws Exception {
        String token = registerAndGetToken("isolation@runtime.com", "Password123!", "Iso");
        String projectId = createProject(token, "Iso Project");
        String contractId = ingestContract(token, projectId, "Pet Contract", PET_STORE_OPENAPI);

        String runtime1 = startRuntime(token, projectId, contractId, 1);
        String runtime2 = startRuntime(token, projectId, contractId, 1);

        // Add pet to Runtime 1
        mockMvc.perform(post("/mock/" + runtime1 + "/pets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Pet One", "tag", "dog"))))
                .andExpect(status().isCreated());

        // Add different pet to Runtime 2
        mockMvc.perform(post("/mock/" + runtime2 + "/pets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Pet Two", "tag", "cat"))))
                .andExpect(status().isCreated());

        // Verify Runtime 1 contains Pet One and NOT Pet Two
        mockMvc.perform(get("/mock/" + runtime1 + "/pets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Pet One')]", notNullValue()))
                .andExpect(jsonPath("$[?(@.name == 'Pet Two')]", hasSize(0)));

        // Verify Runtime 2 contains Pet Two and NOT Pet One
        mockMvc.perform(get("/mock/" + runtime2 + "/pets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Pet Two')]", notNullValue()))
                .andExpect(jsonPath("$[?(@.name == 'Pet One')]", hasSize(0)));
    }

    @Test
    @DisplayName("32. Method Not Allowed and Route Not Found handling")
    void testMethodNotAllowedAndNotFound() throws Exception {
        String token = registerAndGetToken("routes@runtime.com", "Password123!", "Route");
        String projectId = createProject(token, "Route Project");
        String contractId = ingestContract(token, projectId, "Pet Contract", PET_STORE_OPENAPI);
        String runtimeId = startRuntime(token, projectId, contractId, 1);

        // Unknown route -> 404
        mockMvc.perform(get("/mock/" + runtimeId + "/nonexistent/endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("Route not found")));

        // Unsupported method on /pets/{petId} (e.g. POST on /pets/{petId}) -> 405 Method Not Allowed
        mockMvc.perform(post("/mock/" + runtimeId + "/pets/123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error", containsString("not allowed")));
    }

    @Test
    @DisplayName("33. Stop and Delete Runtime Lifecycle Operations")
    void testStopAndDeleteRuntime() throws Exception {
        String token = registerAndGetToken("lifecycle@runtime.com", "Password123!", "Life");
        String projectId = createProject(token, "Life Project");
        String contractId = ingestContract(token, projectId, "Pet Contract", PET_STORE_OPENAPI);
        String runtimeId = startRuntime(token, projectId, contractId, 1);

        // Check runtime status
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("RUNNING")))
                .andExpect(jsonPath("$.data.endpointsCount", is(5)));

        // Stop runtime
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/stop")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("STOPPED")));

        // Calling mock after stop returns 404
        mockMvc.perform(get("/mock/" + runtimeId + "/pets"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("not running or does not exist")));

        // Delete runtime
        mockMvc.perform(delete("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Verify deleted in DB
        assertThat(runtimeRepository.existsById(UUID.fromString(runtimeId))).isFalse();
    }
}
