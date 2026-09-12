package com.mockapilab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.runtime.dto.GenerateDataRequest;
import com.mockapilab.modules.runtime.dto.GenerationJobEvent;
import com.mockapilab.modules.runtime.dto.StartRuntimeRequest;
import com.mockapilab.modules.runtime.model.GenerationJobStatus;
import com.mockapilab.modules.runtime.service.GenerationJobService;
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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KafkaAsyncGenerationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GenerationJobService generationJobService;

    private static final String PET_STORE_OPENAPI = """
            openapi: 3.0.3
            info:
              title: Async Pet Store
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
                      description: Created pet
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Pet'
            components:
              schemas:
                Pet:
                  type: object
                  required:
                    - id
                    - name
                  properties:
                    id:
                      type: integer
                      format: int64
                    name:
                      type: string
                    tag:
                      type: string
            """;

    private String token;
    private String projectId;
    private String runtimeId;

    @BeforeEach
    void setUp() throws Exception {
        token = registerAndGetToken("kafka-async-" + UUID.randomUUID() + "@test.com", "Password123!", "Kafka Tester");
        projectId = createProject(token, "Async Kafka Project");
        String contractId = ingestContract(token, projectId, "Async Pet Contract", PET_STORE_OPENAPI);
        runtimeId = startRuntime(token, projectId, contractId, 1);
    }

    @Test
    @DisplayName("End-to-End: Submit generation job -> 202 Accepted -> Query job status -> Inspect mock data -> Idempotency")
    void testEndToEndAsyncGeneration() throws Exception {
        // 1. Initial GET /pets is empty []
        mockMvc.perform(get("/mock/" + runtimeId + "/pets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 2. Submit async generation job for /pets (count 5, seed 42)
        GenerateDataRequest request = new GenerateDataRequest("/pets", 5, 42L);
        MvcResult submitResult = mockMvc.perform(post("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/data/generate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.jobId", notNullValue()))
                .andExpect(jsonPath("$.data.runtimeId", is(runtimeId)))
                .andExpect(jsonPath("$.data.collection", is("/pets")))
                .andExpect(jsonPath("$.data.count", is(5)))
                .andExpect(jsonPath("$.data.requestedSeed", is(42)))
                .andReturn();

        JsonNode submitNode = objectMapper.readTree(submitResult.getResponse().getContentAsString());
        String jobId = submitNode.path("data").path("jobId").asText();
        assertThat(jobId).isNotBlank();

        // 3. Query job status by ID
        MvcResult getJobResult = mockMvc.perform(get("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/generation-jobs/" + jobId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.jobId", is(jobId)))
                .andExpect(jsonPath("$.data.status", is("COMPLETED")))
                .andExpect(jsonPath("$.data.effectiveSeed", is(42)))
                .andExpect(jsonPath("$.data.startedAt", notNullValue()))
                .andExpect(jsonPath("$.data.completedAt", notNullValue()))
                .andReturn();

        // 4. Query jobs list for runtime
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/generation-jobs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].jobId", is(jobId)));

        // 5. Verify Mock Gateway GET /pets returns the 5 generated entities
        mockMvc.perform(get("/mock/" + runtimeId + "/pets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].name", notNullValue()));

        // 6. Security Isolation: Another user cannot access this generation job
        String foreignToken = registerAndGetToken("foreign-" + UUID.randomUUID() + "@test.com", "Password123!", "Foreign");
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/generation-jobs/" + jobId)
                        .header("Authorization", "Bearer " + foreignToken))
                .andExpect(status().isForbidden());

        // 7. Idempotency test: Re-dispatching the same event does not mutate or duplicate entities
        GenerationJobEvent duplicateEvent = new GenerationJobEvent(
                UUID.fromString(jobId),
                UUID.fromString(runtimeId),
                UUID.fromString(projectId),
                "/pets",
                5,
                42L
        );
        generationJobService.processJob(duplicateEvent);

        // Verify count is still exactly 5
        mockMvc.perform(get("/mock/" + runtimeId + "/pets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
    }

    private String registerAndGetToken(String email, String password, String displayName) throws Exception {
        String regBody = String.format("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"%s\"}", email, password, displayName);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regBody))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(res.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    private String createProject(String token, String name) throws Exception {
        String body = String.format("{\"name\":\"%s\",\"description\":\"Desc\"}", name);
        MvcResult res = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(res.getResponse().getContentAsString()).path("data").path("id").asText();
    }

    private String ingestContract(String token, String projectId, String name, String openApiContent) throws Exception {
        String body = objectMapper.writeValueAsString(new IngestContractDto(name, openApiContent, "OPENAPI"));
        MvcResult res = mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(res.getResponse().getContentAsString()).path("data").path("id").asText();
    }

    private String startRuntime(String token, String projectId, String contractId, int versionNumber) throws Exception {
        StartRuntimeRequest req = new StartRuntimeRequest("Test Runtime");
        MvcResult res = mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts/" + contractId + "/versions/" + versionNumber + "/runtime")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(res.getResponse().getContentAsString()).path("data").path("id").asText();
    }

    private record IngestContractDto(String name, String content, String sourceType) {}
}