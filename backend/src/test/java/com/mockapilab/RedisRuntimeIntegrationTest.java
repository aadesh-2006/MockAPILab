package com.mockapilab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.auth.dto.RegisterRequest;
import com.mockapilab.modules.auth.repository.UserRepository;
import com.mockapilab.modules.contract.dto.IngestContractRequest;
import com.mockapilab.modules.contract.model.ContractSourceType;
import com.mockapilab.modules.contract.repository.ContractRepository;
import com.mockapilab.modules.contract.repository.ContractVersionRepository;
import com.mockapilab.modules.project.dto.CreateProjectRequest;
import com.mockapilab.modules.project.repository.ProjectRepository;
import com.mockapilab.modules.runtime.dto.GenerateDataRequest;
import com.mockapilab.modules.runtime.dto.StartRuntimeRequest;
import com.mockapilab.modules.runtime.repository.MockRuntimeRepository;
import com.mockapilab.modules.runtime.state.MockRedisTemplateBuilder;
import com.mockapilab.modules.runtime.state.RedisRuntimeStateStore;
import com.mockapilab.modules.runtime.state.RuntimeStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mockapilab.runtime.state-store=redis",
        "spring.main.allow-bean-definition-overriding=true"
})
class RedisRuntimeIntegrationTest {

    @TestConfiguration
    static class TestRedisConfig {
        @Bean
        @Primary
        public StringRedisTemplate stringRedisTemplate() {
            return new MockRedisTemplateBuilder().build();
        }
    }

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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
              title: Petstore Redis Mock API
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
                      example: "Buddy"
                    species:
                      type: string
                      example: "Dog"
            """;

    @Test
    @DisplayName("Redis Store Active: End-to-end data generation, mock gateway retrieval, and runtime isolation")
    void testRedisBackedRuntimeEndToEnd() throws Exception {
        // Assert that the active bean is indeed RedisRuntimeStateStore
        assertThat(stateStore).isInstanceOf(RedisRuntimeStateStore.class);

        // 1. Register user & get JWT token
        RegisterRequest registerReq = new RegisterRequest("redis_dev@example.com", "Password123!", "Redis Developer");
        MvcResult authResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String token = objectMapper.readTree(authResult.getResponse().getContentAsString())
                .get("data").get("token").asText();

        // 2. Create Project
        CreateProjectRequest projectReq = new CreateProjectRequest("Redis Mock Project", "Testing Redis state");
        MvcResult projResult = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(projectReq)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID projectId = UUID.fromString(objectMapper.readTree(projResult.getResponse().getContentAsString())
                .get("data").get("id").asText());

        // 3. Ingest OpenAPI contract
        IngestContractRequest contractReq = new IngestContractRequest(
                "Pet Store Contract",
                "Contract for Redis testing",
                PET_STORE_OPENAPI,
                ContractSourceType.OPENAPI
        );
        MvcResult contractResult = mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(contractReq)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID contractId = UUID.fromString(objectMapper.readTree(contractResult.getResponse().getContentAsString())
                .get("data").get("id").asText());

        // 4. Start Runtime A
        StartRuntimeRequest startReq = new StartRuntimeRequest("Runtime A - Redis");
        MvcResult runtimeResultA = mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts/" + contractId + "/versions/1/runtime")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startReq)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID runtimeIdA = UUID.fromString(objectMapper.readTree(runtimeResultA.getResponse().getContentAsString())
                .get("data").get("id").asText());

        // 5. Start Runtime B
        StartRuntimeRequest startReqB = new StartRuntimeRequest("Runtime B - Redis");
        MvcResult runtimeResultB = mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts/" + contractId + "/versions/1/runtime")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startReqB)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID runtimeIdB = UUID.fromString(objectMapper.readTree(runtimeResultB.getResponse().getContentAsString())
                .get("data").get("id").asText());

        // 6. Generate 5 pets in Runtime A
        GenerateDataRequest generateReq = new GenerateDataRequest("/pets", 5, 42L);
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/runtimes/" + runtimeIdA + "/data/generate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(generateReq)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.collection", is("/pets")))
                .andExpect(jsonPath("$.data.count", is(5)))
                .andExpect(jsonPath("$.data.requestedSeed", is(42)));

        // 7. Verify Runtime A mock GET /pets returns 5 entities from Redis state
        mockMvc.perform(get("/mock/" + runtimeIdA + "/pets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));

        // 8. Verify Runtime B mock GET /pets is empty (Strict Runtime Isolation in Redis)
        mockMvc.perform(get("/mock/" + runtimeIdB + "/pets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 9. Verify Multi-Instance State Sharing: A fresh RedisRuntimeStateStore instance reading the same Redis store
        // sees Runtime A's generated pets
        RedisRuntimeStateStore freshStoreInstance = new RedisRuntimeStateStore(stringRedisTemplate, objectMapper);
        List<Map<String, Object>> petsFromFreshStore = freshStoreInstance.getCollection(runtimeIdA, "/pets");
        assertThat(petsFromFreshStore).hasSize(5);
        assertThat(freshStoreInstance.getCollectionCount(runtimeIdA)).isEqualTo(1);
        assertThat(freshStoreInstance.getEntityCount(runtimeIdA)).isEqualTo(5);
    }
}