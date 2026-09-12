package com.mockapilab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.auth.dto.LoginRequest;
import com.mockapilab.modules.auth.dto.RegisterRequest;
import com.mockapilab.modules.contract.dto.IngestContractRequest;
import com.mockapilab.modules.contract.model.ContractSourceType;
import com.mockapilab.modules.project.dto.CreateProjectRequest;
import com.mockapilab.modules.scenario.dto.ScenarioRequest;
import com.mockapilab.modules.scenario.model.ScenarioAction;
import com.mockapilab.modules.scenario.model.ScenarioStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScenarioIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String authToken;
    private String otherAuthToken;
    private String projectId;
    private String runtimeId;

    private static final String SAMPLE_OPENAPI_SPEC = """
            openapi: 3.0.3
            info:
              title: Users Service
              version: 1.0.0
            paths:
              /users:
                get:
                  summary: List all users
                  responses:
                    '200':
                      description: List of users
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              $ref: '#/components/schemas/User'
                post:
                  summary: Create a user
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/User'
                  responses:
                    '201':
                      description: User created
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/User'
              /users/{id}:
                get:
                  summary: Get user by ID
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema:
                        type: string
                  responses:
                    '200':
                      description: User details
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/User'
                    '404':
                      description: User not found
            components:
              schemas:
                User:
                  type: object
                  required:
                    - name
                    - email
                  properties:
                    id:
                      type: string
                    name:
                      type: string
                    email:
                      type: string
            """;

    @BeforeEach
    void setUp() throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String ownerEmail = "owner-" + unique + "@example.com";
        String otherEmail = "other-" + unique + "@example.com";

        // Register owner
        RegisterRequest registerOwner = new RegisterRequest(ownerEmail, "Password123!", "Scenario Owner");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerOwner)))
                .andExpect(status().isCreated());

        // Login owner
        LoginRequest loginOwner = new LoginRequest(ownerEmail, "Password123!");
        MvcResult ownerLoginRes = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginOwner)))
                .andExpect(status().isOk())
                .andReturn();
        authToken = "Bearer " + objectMapper.readTree(ownerLoginRes.getResponse().getContentAsString()).path("data").path("token").asText();

        // Register other user
        RegisterRequest registerOther = new RegisterRequest(otherEmail, "Password123!", "Other User");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerOther)))
                .andExpect(status().isCreated());

        LoginRequest loginOther = new LoginRequest(otherEmail, "Password123!");
        MvcResult otherLoginRes = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginOther)))
                .andExpect(status().isOk())
                .andReturn();
        otherAuthToken = "Bearer " + objectMapper.readTree(otherLoginRes.getResponse().getContentAsString()).path("data").path("token").asText();

        // Create Project
        CreateProjectRequest projectReq = new CreateProjectRequest("Scenario Project", "Testing M9 Scenarios");
        MvcResult projectRes = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projectReq)))
                .andExpect(status().isCreated())
                .andReturn();
        projectId = objectMapper.readTree(projectRes.getResponse().getContentAsString()).path("data").path("id").asText();

        // Ingest contract
        IngestContractRequest ingestReq = new IngestContractRequest("Users API", "Users Service Contract", SAMPLE_OPENAPI_SPEC, ContractSourceType.OPENAPI);
        MvcResult contractRes = mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts")
                        .header("Authorization", authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ingestReq)))
                .andExpect(status().isCreated())
                .andReturn();
        String contractId = objectMapper.readTree(contractRes.getResponse().getContentAsString()).path("data").path("id").asText();

        // Start runtime
        MvcResult runtimeRes = mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts/" + contractId + "/versions/1/runtime")
                        .header("Authorization", authToken))
                .andExpect(status().isCreated())
                .andReturn();
        runtimeId = objectMapper.readTree(runtimeRes.getResponse().getContentAsString()).path("data").path("id").asText();
    }

    @Test
    @DisplayName("End-to-End: FORCE_STATUS failure injection and Redis state unmutated check")
    void scenario_ForceStatus_FailureInjection_And_StatePreservation() throws Exception {
        // 1. Initially, GET /mock/{runtimeId}/users returns 200 OK with empty array []
        mockMvc.perform(get("/mock/" + runtimeId + "/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        // 2. Create scenario to FORCE_STATUS 401 on GET /users
        ScenarioRequest authFailureScenario = new ScenarioRequest(
                "Unauthorized Users",
                "Simulate 401 Unauthorized",
                ScenarioStatus.ACTIVE,
                "/users",
                "GET",
                ScenarioAction.FORCE_STATUS,
                401,
                null,
                null,
                null
        );

        MvcResult scenarioResult = mockMvc.perform(post("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/scenarios")
                        .header("Authorization", authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(authFailureScenario)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.statusCode").value(401))
                .andReturn();

        String scenarioId = objectMapper.readTree(scenarioResult.getResponse().getContentAsString()).path("data").path("id").asText();

        // 3. GET /mock/{runtimeId}/users now receives injected 401 Unauthorized
        mockMvc.perform(get("/mock/" + runtimeId + "/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("SCENARIO_INJECTED_FAILURE"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.scenarioId").value(scenarioId));

        // 4. Create scenario to FORCE_STATUS 500 on POST /users
        ScenarioRequest postFailureScenario = new ScenarioRequest(
                "POST 500 Failure",
                "Failing creates",
                ScenarioStatus.ACTIVE,
                "/users",
                "POST",
                ScenarioAction.FORCE_STATUS,
                500,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/scenarios")
                        .header("Authorization", authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(postFailureScenario)))
                .andExpect(status().isCreated());

        // 5. POST /mock/{runtimeId}/users fails with 500
        mockMvc.perform(post("/mock/" + runtimeId + "/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alice\",\"email\":\"alice@example.com\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("SCENARIO_INJECTED_FAILURE"));

        // 6. Disable the 401 GET scenario
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/scenarios/" + scenarioId + "/disable")
                        .header("Authorization", authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        // 7. CRITICAL VERIFICATION: State was NOT mutated by the failed POST!
        // GET /mock/{runtimeId}/users returns 200 OK and 0 items
        mockMvc.perform(get("/mock/" + runtimeId + "/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("End-to-End: maxExecutions exhausts limit and resumes normal mock behavior")
    void scenario_MaxExecutions_ExhaustsAndResumes() throws Exception {
        ScenarioRequest rateLimitScenario = new ScenarioRequest(
                "Rate Limit Test",
                "Limit 2 requests",
                ScenarioStatus.ACTIVE,
                "/users",
                "GET",
                ScenarioAction.FORCE_STATUS,
                429,
                null,
                null,
                2
        );

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/scenarios")
                        .header("Authorization", authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rateLimitScenario)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.maxExecutions").value(2));

        // 1st request -> 429
        mockMvc.perform(get("/mock/" + runtimeId + "/users"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        // 2nd request -> 429
        mockMvc.perform(get("/mock/" + runtimeId + "/users"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        // 3rd request -> Limit exhausted -> 200 OK normal mock response!
        mockMvc.perform(get("/mock/" + runtimeId + "/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Project ownership isolation rejects unauthorized users from scenario management")
    void scenario_OwnershipIsolation_Enforced() throws Exception {
        ScenarioRequest request = new ScenarioRequest(
                "Unauthorized Access",
                "Desc",
                ScenarioStatus.ACTIVE,
                "/users",
                "GET",
                ScenarioAction.FORCE_STATUS,
                500,
                null,
                null,
                null
        );

        // Attempting to create scenario in another user's project -> 403 Forbidden
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/scenarios")
                        .header("Authorization", otherAuthToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }
}
