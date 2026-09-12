package com.mockapilab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.auth.dto.RegisterRequest;
import com.mockapilab.modules.contract.drift.dto.DriftAnalysisRequest;
import com.mockapilab.modules.contract.dto.IngestContractRequest;
import com.mockapilab.modules.contract.model.ContractSourceType;
import com.mockapilab.modules.project.dto.CreateProjectRequest;
import com.mockapilab.modules.runtime.dto.GenerateDataRequest;
import com.mockapilab.modules.runtime.dto.GenerationJobEvent;
import com.mockapilab.modules.runtime.service.GenerationJobService;
import com.mockapilab.modules.scenario.dto.ScenarioRequest;
import com.mockapilab.modules.scenario.model.ScenarioAction;
import com.mockapilab.modules.scenario.model.ScenarioStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EndToEndPlatformIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GenerationJobService generationJobService;

    private static final String V1_OPENAPI = """
            openapi: 3.0.3
            info:
              title: Petstore Service API
              version: 1.0.0
            paths:
              /pets:
                get:
                  summary: List all pets
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
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/Pet'
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
                  parameters:
                    - name: petId
                      in: path
                      required: true
                      schema:
                        type: string
                  responses:
                    '200':
                      description: Pet found
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Pet'
            components:
              schemas:
                Pet:
                  type: object
                  required:
                    - name
                  properties:
                    id:
                      type: string
                    name:
                      type: string
                    tag:
                      type: string
            """;

    private static final String V2_OPENAPI_WITH_DRIFT = """
            openapi: 3.0.3
            info:
              title: Petstore Service API
              version: 2.0.0
            paths:
              /pets:
                get:
                  summary: List all pets
                  parameters:
                    - name: apiKey
                      in: header
                      required: true
                      schema:
                        type: string
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
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/Pet'
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
                  parameters:
                    - name: petId
                      in: path
                      required: true
                      schema:
                        type: string
                  responses:
                    '200':
                      description: Pet found
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Pet'
            components:
              schemas:
                Pet:
                  type: object
                  required:
                    - name
                    - breed
                  properties:
                    id:
                      type: string
                    name:
                      type: string
                    tag:
                      type: string
                    breed:
                      type: string
                    status:
                      type: string
            """;

    @Test
    @DisplayName("Complete E2E Platform Lifecycle: Auth -> Project -> Contract V1 -> Runtime -> Data Gen -> Public Mock -> Scenarios -> Contract V2 -> Drift Report -> Actuator")
    void testCompletePlatformLifecycle() throws Exception {
        // 1. User Registration
        RegisterRequest registerRequest = new RegisterRequest("e2e.dev@mockapilab.io", "SecurePassword123!", "E2E Developer");
        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.token", notNullValue()))
                .andReturn();

        JsonNode registerJson = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        String token = registerJson.path("data").path("token").asText();
        String authHeader = "Bearer " + token;

        // 2. Create Project
        CreateProjectRequest projectRequest = new CreateProjectRequest("E2E Pet Store Platform", "Full lifecycle test project");
        MvcResult projectResult = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projectRequest)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andReturn();

        UUID projectId = UUID.fromString(objectMapper.readTree(projectResult.getResponse().getContentAsString()).path("data").path("id").asText());

        // 3. Ingest Contract V1
        IngestContractRequest contractRequest = new IngestContractRequest("Petstore Contract", "Petstore API", V1_OPENAPI, ContractSourceType.OPENAPI);
        MvcResult contractResult = mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(contractRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.name", is("Petstore Contract")))
                .andExpect(jsonPath("$.data.latestVersion", is(1)))
                .andReturn();

        UUID contractId = UUID.fromString(objectMapper.readTree(contractResult.getResponse().getContentAsString()).path("data").path("id").asText());

        // 4. Start Mock Runtime
        MvcResult runtimeResult = mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts/" + contractId + "/versions/1/runtime")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.status", is("RUNNING")))
                .andReturn();

        UUID runtimeId = UUID.fromString(objectMapper.readTree(runtimeResult.getResponse().getContentAsString()).path("data").path("id").asText());

        // 5. Submit Async Data Generation Job & Process
        GenerateDataRequest dataGenRequest = new GenerateDataRequest("/pets", 5, 42L);
        MvcResult genJobResult = mockMvc.perform(post("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/data/generate")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dataGenRequest)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId", notNullValue()))
                .andExpect(jsonPath("$.data.status", notNullValue()))
                .andReturn();

        UUID jobId = UUID.fromString(objectMapper.readTree(genJobResult.getResponse().getContentAsString()).path("data").path("jobId").asText());

        // Verify Job status COMPLETED
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/generation-jobs/" + jobId)
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("COMPLETED")))
                .andExpect(jsonPath("$.data.effectiveSeed", is(42)));

        // 6. Public Unauthenticated Mock Gateway: GET /pets (reads generated state)
        MvcResult mockListResult = mockMvc.perform(get("/mock/" + runtimeId + "/pets"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].name", notNullValue()))
                .andReturn();

        JsonNode petsArray = objectMapper.readTree(mockListResult.getResponse().getContentAsString());
        String petId = petsArray.get(0).path("id").asText();

        // Public Mock Gateway: GET /pets/{id}
        mockMvc.perform(get("/mock/" + runtimeId + "/pets/" + petId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(petId)));

        // Public Mock Gateway: POST /pets (stateful entity creation)
        String newPetPayload = "{\"name\": \"Fluffy\", \"tag\": \"cat\"}";
        mockMvc.perform(post("/mock/" + runtimeId + "/pets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newPetPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Fluffy")))
                .andExpect(jsonPath("$.id", notNullValue()));

        // 7. Interactive Scenario Injection: FORCE_STATUS 429
        ScenarioRequest scenarioReq = new ScenarioRequest(
                "Rate Limit Pets List",
                "Simulate 429 Too Many Requests on GET /pets",
                ScenarioStatus.ACTIVE,
                "/pets",
                "GET",
                ScenarioAction.FORCE_STATUS,
                429,
                null,
                null,
                2
        );

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/scenarios")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(scenarioReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status", is("ACTIVE")));

        // First mock request triggers 429
        mockMvc.perform(get("/mock/" + runtimeId + "/pets"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", is("SCENARIO_INJECTED_FAILURE")))
                .andExpect(jsonPath("$.status", is(429)));

        // Second mock request triggers 429 (max executions = 2)
        mockMvc.perform(get("/mock/" + runtimeId + "/pets"))
                .andExpect(status().isTooManyRequests());

        // Third mock request passes normally (max executions exceeded)
        mockMvc.perform(get("/mock/" + runtimeId + "/pets"))
                .andExpect(status().isOk());

        // 8. Ingest Contract Version 2 (with breaking & non-breaking changes)
        IngestContractRequest v2Request = new IngestContractRequest("Petstore Contract", "Petstore API V2", V2_OPENAPI_WITH_DRIFT, ContractSourceType.OPENAPI);
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts/" + contractId + "/versions")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(v2Request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.versionNumber", is(2)));

        // 9. Contract Drift Analysis (Version 1 -> Version 2)
        DriftAnalysisRequest driftReq = new DriftAnalysisRequest(1, 2);
        MvcResult driftResult = mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts/" + contractId + "/drift")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(driftReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fromVersionNumber", is(1)))
                .andExpect(jsonPath("$.data.toVersionNumber", is(2)))
                .andExpect(jsonPath("$.data.breakingChangeCount", greaterThan(0)))
                .andExpect(jsonPath("$.data.nonBreakingChangeCount", greaterThan(0)))
                .andExpect(jsonPath("$.data.overallSeverity", is("HIGH")))
                .andReturn();

        UUID reportId = UUID.fromString(objectMapper.readTree(driftResult.getResponse().getContentAsString()).path("data").path("id").asText());

        // Retrieve Drift Report by ID
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/contracts/" + contractId + "/drift/" + reportId)
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(reportId.toString())))
                .andExpect(jsonPath("$.data.changes", not(empty())));

        // Verify runtime state is unaffected by drift analysis
        mockMvc.perform(get("/mock/" + runtimeId + "/pets"))
                .andExpect(status().isOk());

        // 10. Actuator Health & Metrics
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.components.runtimeStateStore", notNullValue()));

        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names", hasItem("mockapilab.mock.requests.total")))
                .andExpect(jsonPath("$.names", hasItem("mockapilab.scenario.triggered.total")))
                .andExpect(jsonPath("$.names", hasItem("mockapilab.generation.jobs.total")))
                .andExpect(jsonPath("$.names", hasItem("mockapilab.drift.analyses.total")));
    }
}
