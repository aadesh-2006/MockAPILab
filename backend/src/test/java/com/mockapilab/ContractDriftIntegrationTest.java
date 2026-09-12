package com.mockapilab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.auth.dto.RegisterRequest;
import com.mockapilab.modules.contract.dto.IngestContractRequest;
import com.mockapilab.modules.contract.drift.dto.DriftAnalysisRequest;
import com.mockapilab.modules.contract.drift.model.DriftSeverity;
import com.mockapilab.modules.project.dto.CreateProjectRequest;
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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContractDriftIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String obtainAccessToken(String email, String password) throws Exception {
        RegisterRequest registerReq = new RegisterRequest(email, password, "Drift Tester");
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerReq)));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    @Test
    @DisplayName("Full Contract Drift Detection Workflow: Ingest v1 and v2, analyze drift, list and get reports")
    void testContractDriftFullWorkflow() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String token = obtainAccessToken("drift-user-" + uniqueSuffix + "@mockapilab.com", "password123");

        // 1. Create project
        CreateProjectRequest projectReq = new CreateProjectRequest("Drift Project", "Contract Drift Testing");
        String projectRespStr = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projectReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String projectId = objectMapper.readTree(projectRespStr).path("data").path("id").asText();

        // 2. Ingest Contract Version 1
        String v1OpenApi = """
                openapi: 3.0.3
                info:
                  title: Users API
                  version: 1.0.0
                paths:
                  /users:
                    get:
                      operationId: listUsers
                      parameters:
                        - name: limit
                          in: query
                          required: false
                          schema:
                            type: integer
                      responses:
                        '200':
                          description: Successful response
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  id:
                                    type: string
                                  name:
                                    type: string
                """;

        IngestContractRequest v1Request = new IngestContractRequest("Users API", "User management", v1OpenApi, null);
        String contractRespStr = mockMvc.perform(post("/api/v1/projects/{projectId}/contracts", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(v1Request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String contractId = objectMapper.readTree(contractRespStr).path("data").path("id").asText();

        // 3. Ingest Contract Version 2 (added endpoint /orders, added response field 'email' to /users, removed param 'limit', added required param 'apiKey')
        String v2OpenApi = """
                openapi: 3.0.3
                info:
                  title: Users API
                  version: 2.0.0
                paths:
                  /users:
                    get:
                      operationId: listUsers
                      parameters:
                        - name: apiKey
                          in: header
                          required: true
                          schema:
                            type: string
                      responses:
                        '200':
                          description: Successful response
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  id:
                                    type: string
                                  name:
                                    type: string
                                  email:
                                    type: string
                  /orders:
                    get:
                      operationId: listOrders
                      responses:
                        '200':
                          description: Orders list
                """;

        IngestContractRequest v2Request = new IngestContractRequest("Users API v2", "Updated user API", v2OpenApi, null);
        mockMvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/versions", projectId, contractId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(v2Request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.versionNumber", is(2)));

        // 4. Run Drift Analysis between v1 and v2
        DriftAnalysisRequest driftReq = new DriftAnalysisRequest(1, 2);
        String driftRespStr = mockMvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/drift", projectId, contractId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(driftReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fromVersionNumber", is(1)))
                .andExpect(jsonPath("$.data.toVersionNumber", is(2)))
                .andExpect(jsonPath("$.data.breakingChangeCount", is(1))) // apiKey added (required=true)
                .andExpect(jsonPath("$.data.nonBreakingChangeCount", is(3))) // /orders added, email added, limit param removed (was optional)
                .andExpect(jsonPath("$.data.informationalChangeCount", is(1))) // metadata version changed from 1.0.0 to 2.0.0
                .andExpect(jsonPath("$.data.overallSeverity", is(DriftSeverity.HIGH.name())))
                .andExpect(jsonPath("$.data.changes", hasSize(5)))
                .andReturn().getResponse().getContentAsString();

        String reportId = objectMapper.readTree(driftRespStr).path("data").path("id").asText();

        // 5. List Drift Reports
        mockMvc.perform(get("/api/v1/projects/{projectId}/contracts/{contractId}/drift", projectId, contractId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id", is(reportId)));

        // 6. Get Single Drift Report
        mockMvc.perform(get("/api/v1/projects/{projectId}/contracts/{contractId}/drift/{reportId}", projectId, contractId, reportId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(reportId)))
                .andExpect(jsonPath("$.data.breakingChangeCount", is(1)))
                .andExpect(jsonPath("$.data.changes", hasSize(5)));

        // 7. Security: Another user cannot access reports
        String user2Token = obtainAccessToken("other-drift-" + uniqueSuffix + "@mockapilab.com", "password123");
        mockMvc.perform(get("/api/v1/projects/{projectId}/contracts/{contractId}/drift", projectId, contractId)
                        .header("Authorization", "Bearer " + user2Token))
                .andExpect(status().isForbidden());

        // 8. Error handling: Non-existent version
        DriftAnalysisRequest invalidReq = new DriftAnalysisRequest(1, 99);
        mockMvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/drift", projectId, contractId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isNotFound());
    }
}
