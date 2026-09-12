package com.mockapilab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.ai.dto.AiExtractContractRequest;
import com.mockapilab.modules.ai.dto.ExtractionInputType;
import com.mockapilab.modules.ai.exception.AiProviderException;
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
import com.mockapilab.modules.contract.model.ContractSourceType;
import com.mockapilab.modules.contract.repository.ContractRepository;
import com.mockapilab.modules.contract.repository.ContractVersionRepository;
import com.mockapilab.modules.project.dto.CreateProjectRequest;
import com.mockapilab.modules.project.dto.ProjectResponse;
import com.mockapilab.modules.project.service.ProjectService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AI Contract Extraction Integration Tests")
class AiContractExtractionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private ContractVersionRepository contractVersionRepository;

    @MockBean
    private AiProvider mockAiProvider;

    private String user1Token;
    private UUID user1Id;
    private UUID project1Id;

    private String user2Token;
    private UUID user2Id;

    @BeforeEach
    void setUp() {
        when(mockAiProvider.getProviderName()).thenReturn("mock-gemini");

        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);

        // User 1
        AuthResponse auth1 = authService.register(new RegisterRequest(
                "user1_" + uniqueSuffix,
                "user1_" + uniqueSuffix + "@mockapilab.io",
                "Password123!"
        ));
        user1Token = auth1.token();
        user1Id = auth1.user().id();

        ProjectResponse p1 = projectService.createProject(
                new CreateProjectRequest("AI Test Project", "Testing AI Contract Extraction"),
                user1Id
        );
        project1Id = p1.id();

        // User 2
        AuthResponse auth2 = authService.register(new RegisterRequest(
                "user2_" + uniqueSuffix,
                "user2_" + uniqueSuffix + "@mockapilab.io",
                "Password123!"
        ));
        user2Token = auth2.token();
        user2Id = auth2.user().id();
    }

    @Test
    @DisplayName("Successfully extracts contract from natural language description and persists version 1")
    void extractContract_description_success() throws Exception {
        AiCandidateSchema userSchema = new AiCandidateSchema(
                "object", null, "User entity", false, null, null, null,
                Map.of(
                        "id", new AiCandidateSchema("string", "uuid", null, false, null, null, null, null, null, null, null, null, null, null, null, null),
                        "name", new AiCandidateSchema("string", null, null, false, null, null, null, null, null, null, null, null, null, null, null, null),
                        "email", new AiCandidateSchema("string", "email", null, false, null, null, null, null, null, null, null, null, null, null, null, null)
                ),
                List.of("name", "email"),
                null, null, null, null, null, null, null
        );

        AiCandidateEndpoint listUsers = new AiCandidateEndpoint(
                "/users",
                "GET",
                "listUsers",
                "List users",
                "Returns users array",
                null,
                null,
                List.of(new AiCandidateResponse("200", "OK", "application/json", new AiCandidateSchema("array", null, null, false, null, null, null, null, null, new AiCandidateSchema(null, null, null, false, null, null, null, null, null, null, "User", null, null, null, null, null), null, null, null, null, null, null)))
        );

        AiCandidateContract candidate = new AiCandidateContract(
                "Users API",
                "Extracted users API",
                "1.0.0",
                List.of(listUsers),
                Map.of("User", userSchema)
        );

        when(mockAiProvider.extractCandidateContract(any(), eq(ExtractionInputType.DESCRIPTION))).thenReturn(candidate);

        AiExtractContractRequest request = new AiExtractContractRequest(
                "Create a users API with GET /users returning a list of users.",
                ExtractionInputType.DESCRIPTION,
                "Extracted Users Service",
                "Custom description"
        );

        mockMvc.perform(post("/api/v1/projects/{projectId}/contracts/ai-extract", project1Id)
                        .header("Authorization", "Bearer " + user1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Extracted Users Service"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.sourceType").value("NATURAL_LANGUAGE"))
                .andExpect(jsonPath("$.data.extractedEndpointsCount").value(1))
                .andExpect(jsonPath("$.data.extractedSchemasCount").value(1))
                .andExpect(jsonPath("$.data.candidateTitle").value("Users API"))
                .andExpect(jsonPath("$.data.contract.totalEndpoints").value(1));

        var contracts = contractRepository.findAllByProjectIdOrderByCreatedAtDesc(project1Id);
        assertThat(contracts).hasSize(1);
        var versions = contractVersionRepository.findAllByContractIdOrderByVersionNumberDesc(contracts.get(0).getId());
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getSourceType()).isEqualTo(ContractSourceType.NATURAL_LANGUAGE);
        assertThat(versions.get(0).getNormalizedDefinition().endpoints()).hasSize(1);
    }

    @Test
    @DisplayName("Successfully extracts contract from Spring Boot controller code")
    void extractContract_springBootCode_success() throws Exception {
        AiCandidateSchema productSchema = new AiCandidateSchema(
                "object", null, "Product entity", false, null, null, null,
                Map.of(
                        "id", new AiCandidateSchema("string", "uuid", null, false, null, null, null, null, null, null, null, null, null, null, null, null),
                        "title", new AiCandidateSchema("string", null, null, false, null, null, null, null, null, null, null, null, null, null, null, null),
                        "price", new AiCandidateSchema("number", null, null, false, null, null, null, null, null, null, null, null, null, null, null, null)
                ),
                List.of("title", "price"),
                null, null, null, null, null, null, null
        );

        AiCandidateEndpoint createProduct = new AiCandidateEndpoint(
                "/products",
                "POST",
                "createProduct",
                "Create product",
                "Persists product",
                null,
                new AiCandidateRequestBody("Product payload", true, "application/json", new AiCandidateSchema(null, null, null, false, null, null, null, null, null, null, "Product", null, null, null, null, null)),
                List.of(new AiCandidateResponse("201", "Created", "application/json", new AiCandidateSchema(null, null, null, false, null, null, null, null, null, null, "Product", null, null, null, null, null)))
        );

        AiCandidateContract candidate = new AiCandidateContract(
                "Product Service",
                "Controller extracted API",
                "1.0.0",
                List.of(createProduct),
                Map.of("Product", productSchema)
        );

        when(mockAiProvider.extractCandidateContract(any(), eq(ExtractionInputType.SPRING_BOOT_CODE))).thenReturn(candidate);

        AiExtractContractRequest request = new AiExtractContractRequest(
                "@RestController @RequestMapping(\"/products\") class ProductController { @PostMapping public Product create(@RequestBody Product p) {} }",
                ExtractionInputType.SPRING_BOOT_CODE,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/projects/{projectId}/contracts/ai-extract", project1Id)
                        .header("Authorization", "Bearer " + user1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Product Service"))
                .andExpect(jsonPath("$.data.sourceType").value("AI_CONTROLLER"))
                .andExpect(jsonPath("$.data.extractedEndpointsCount").value(1));
    }

    @Test
    @DisplayName("Rejects extraction when user is not the project owner (HTTP 403)")
    void extractContract_forbidden_returns403() throws Exception {
        AiExtractContractRequest request = new AiExtractContractRequest(
                "GET /users",
                ExtractionInputType.DESCRIPTION,
                "Sneaky Contract",
                null
        );

        mockMvc.perform(post("/api/v1/projects/{projectId}/contracts/ai-extract", project1Id)
                        .header("Authorization", "Bearer " + user2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Access denied")));
    }

    @Test
    @DisplayName("Rejects extraction when unauthenticated (HTTP 401)")
    void extractContract_unauthenticated_returns401() throws Exception {
        AiExtractContractRequest request = new AiExtractContractRequest(
                "GET /users",
                ExtractionInputType.DESCRIPTION,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/projects/{projectId}/contracts/ai-extract", project1Id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Returns HTTP 503 when AI provider fails")
    void extractContract_providerFailure_returns503() throws Exception {
        when(mockAiProvider.extractCandidateContract(any(), any()))
                .thenThrow(new AiProviderException("Gemini rate limit exceeded"));

        AiExtractContractRequest request = new AiExtractContractRequest(
                "GET /users",
                ExtractionInputType.DESCRIPTION,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/projects/{projectId}/contracts/ai-extract", project1Id)
                        .header("Authorization", "Bearer " + user1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("AI extraction provider error")));
    }
}