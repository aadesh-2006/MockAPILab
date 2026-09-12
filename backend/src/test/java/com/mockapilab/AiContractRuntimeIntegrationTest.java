package com.mockapilab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.ai.dto.AiExtractContractRequest;
import com.mockapilab.modules.ai.dto.ExtractionInputType;
import com.mockapilab.modules.ai.model.candidate.AiCandidateContract;
import com.mockapilab.modules.ai.model.candidate.AiCandidateEndpoint;
import com.mockapilab.modules.ai.model.candidate.AiCandidateParameter;
import com.mockapilab.modules.ai.model.candidate.AiCandidateRequestBody;
import com.mockapilab.modules.ai.model.candidate.AiCandidateResponse;
import com.mockapilab.modules.ai.model.candidate.AiCandidateSchema;
import com.mockapilab.modules.ai.provider.AiProvider;
import com.mockapilab.modules.auth.dto.AuthResponse;
import com.mockapilab.modules.auth.dto.RegisterRequest;
import com.mockapilab.modules.auth.service.AuthService;
import com.mockapilab.modules.project.dto.CreateProjectRequest;
import com.mockapilab.modules.project.dto.ProjectResponse;
import com.mockapilab.modules.project.service.ProjectService;
import com.mockapilab.modules.runtime.dto.StartRuntimeRequest;
import com.mockapilab.modules.runtime.dto.GenerateDataRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AI Extracted Contract Runtime Pipeline Integration Test")
class AiContractRuntimeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private ProjectService projectService;

    @MockBean
    private AiProvider mockAiProvider;

    private String userToken;
    private UUID userId;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        when(mockAiProvider.getProviderName()).thenReturn("mock-gemini");

        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        AuthResponse auth = authService.register(new RegisterRequest(
                "ai_runner_" + uniqueSuffix,
                "ai_runner_" + uniqueSuffix + "@mockapilab.io",
                "Password123!"
        ));
        userToken = auth.token();
        userId = auth.user().id();

        ProjectResponse p = projectService.createProject(
                new CreateProjectRequest("AI Runtime Test", "End to end test"),
                userId
        );
        projectId = p.id();
    }

    @Test
    @DisplayName("AI extracted contract can be compiled into a live stateful runtime and used by M4-M7 engines")
    void aiContract_fullRuntimeExecution_succeeds() throws Exception {
        // 1. Prepare Candidate Contract
        AiCandidateSchema itemSchema = new AiCandidateSchema(
                "object", null, "Item Model", false, null, null, null,
                Map.of(
                        "id", new AiCandidateSchema("string", "uuid", null, false, null, null, null, null, null, null, null, null, null, null, null, null),
                        "name", new AiCandidateSchema("string", null, null, false, null, null, null, null, null, null, null, null, null, null, null, null),
                        "price", new AiCandidateSchema("number", null, null, false, null, null, null, null, null, null, null, null, null, null, null, null)
                ),
                List.of("name"),
                null, null, null, null, null, null, null
        );

        AiCandidateEndpoint listItems = new AiCandidateEndpoint(
                "/items", "GET", "listItems", "List items", "Returns all items",
                null, null,
                List.of(new AiCandidateResponse("200", "OK", "application/json", new AiCandidateSchema("array", null, null, false, null, null, null, null, null, new AiCandidateSchema(null, null, null, false, null, null, null, null, null, null, "Item", null, null, null, null, null), null, null, null, null, null, null)))
        );

        AiCandidateEndpoint createItem = new AiCandidateEndpoint(
                "/items", "POST", "createItem", "Create item", "Persists item",
                null,
                new AiCandidateRequestBody("Item payload", true, "application/json", new AiCandidateSchema(null, null, null, false, null, null, null, null, null, null, "Item", null, null, null, null, null)),
                List.of(new AiCandidateResponse("201", "Created", "application/json", new AiCandidateSchema(null, null, null, false, null, null, null, null, null, null, "Item", null, null, null, null, null)))
        );

        AiCandidateEndpoint getItem = new AiCandidateEndpoint(
                "/items/{id}", "GET", "getItem", "Get item", "Find item by ID",
                List.of(new AiCandidateParameter("id", "path", true, "Item ID", new AiCandidateSchema("string", "uuid", null, false, null, null, null, null, null, null, null, null, null, null, null, null))),
                null,
                List.of(new AiCandidateResponse("200", "OK", "application/json", new AiCandidateSchema(null, null, null, false, null, null, null, null, null, null, "Item", null, null, null, null, null)))
        );

        AiCandidateContract candidate = new AiCandidateContract(
                "Items Service", "AI extracted items API", "1.0.0",
                List.of(listItems, createItem, getItem),
                Map.of("Item", itemSchema)
        );

        when(mockAiProvider.extractCandidateContract(any(), eq(ExtractionInputType.DESCRIPTION))).thenReturn(candidate);

        // 2. Ingest via AI Extraction
        AiExtractContractRequest extractRequest = new AiExtractContractRequest(
                "Create an Items API with GET /items, POST /items, and GET /items/{id}",
                ExtractionInputType.DESCRIPTION,
                "AI Items Contract",
                "Generated contract"
        );

        MvcResult extractResult = mockMvc.perform(post("/api/v1/projects/{projectId}/contracts/ai-extract", projectId)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(extractRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String extractJson = extractResult.getResponse().getContentAsString();
        var extractResp = objectMapper.readTree(extractJson);
        String contractId = extractResp.path("data").path("contractId").asText();

        // 3. Start Mock Runtime from Version 1
        StartRuntimeRequest runtimeReq = new StartRuntimeRequest("AI Items Runtime");
        MvcResult runtimeResult = mockMvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/versions/1/runtime", projectId, contractId)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(runtimeReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andReturn();

        var runtimeResp = objectMapper.readTree(runtimeResult.getResponse().getContentAsString());
        String runtimeId = runtimeResp.path("data").path("id").asText();

        // 4. Test Mock Request Dispatcher: POST /mock/{runtimeId}/items
        String newItemPayload = """
                {
                  \"name\": \"Smart Watch\",
                  \"price\": 199.99
                }
                """;

        mockMvc.perform(post("/mock/{runtimeId}/items", runtimeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newItemPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Smart Watch"))
                .andExpect(jsonPath("$.price").value(199.99))
                .andExpect(jsonPath("$.id").isNotEmpty());

        // 5. Test Mock Request Dispatcher: GET /mock/{runtimeId}/items
        mockMvc.perform(get("/mock/{runtimeId}/items", runtimeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Smart Watch"));

        // 6. Test M7 Async Job Dispatcher against AI Contract Runtime
        GenerateDataRequest genReq = new GenerateDataRequest("/items", 3, 42L);
        mockMvc.perform(post("/api/v1/projects/{projectId}/runtimes/{runtimeId}/data/generate", projectId, runtimeId)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value(org.hamcrest.Matchers.isOneOf("QUEUED", "COMPLETED")));
    }
}