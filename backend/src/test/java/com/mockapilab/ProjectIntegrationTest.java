package com.mockapilab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.auth.dto.RegisterRequest;
import com.mockapilab.modules.auth.repository.UserRepository;
import com.mockapilab.modules.project.dto.CreateProjectRequest;
import com.mockapilab.modules.project.dto.UpdateProjectRequest;
import com.mockapilab.modules.project.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
class ProjectIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        projectRepository.deleteAll();
        userRepository.deleteAll();
    }

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

    @Test
    @DisplayName("7. Authenticated user can create project")
    void testCreateProject() throws Exception {
        String token = registerAndGetToken("user1@example.com", "Password123!", "User One");

        CreateProjectRequest createReq = new CreateProjectRequest("E-Commerce Backend Mock", "Mock API for retail checkout");

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.message", is("Project created successfully")))
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.name", is("E-Commerce Backend Mock")))
                .andExpect(jsonPath("$.data.description", is("Mock API for retail checkout")))
                .andExpect(jsonPath("$.data.ownerId", notNullValue()));
    }

    @Test
    @DisplayName("8. Authenticated user can list only their own projects")
    void testListUserProjects() throws Exception {
        String tokenUser1 = registerAndGetToken("user1@example.com", "Password123!", "User One");
        String tokenUser2 = registerAndGetToken("user2@example.com", "Password123!", "User Two");

        // User 1 creates 2 projects
        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectRequest("Project 1", "Desc 1"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenUser1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectRequest("Project 2", "Desc 2"))))
                .andExpect(status().isCreated());

        // User 2 creates 1 project
        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenUser2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectRequest("User2 Project", "Desc"))))
                .andExpect(status().isCreated());

        // User 1 lists projects -> sees 2
        mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenUser1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data", hasSize(2)));

        // User 2 lists projects -> sees 1
        mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenUser2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name", is("User2 Project")));
    }

    @Test
    @DisplayName("9. Authenticated user can retrieve their own project")
    void testGetOwnProject() throws Exception {
        String token = registerAndGetToken("owner@example.com", "Password123!", "Project Owner");

        MvcResult createResult = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectRequest("Payment Gateway API", "Stripe mock"))))
                .andExpect(status().isCreated())
                .andReturn();

        String projectId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.id", is(projectId)))
                .andExpect(jsonPath("$.data.name", is("Payment Gateway API")));
    }

    @Test
    @DisplayName("10. Authenticated user can update and delete their own project")
    void testUpdateAndDeleteOwnProject() throws Exception {
        String token = registerAndGetToken("creator@example.com", "Password123!", "Creator");

        MvcResult createResult = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectRequest("Temporary Project", "To be deleted"))))
                .andExpect(status().isCreated())
                .andReturn();

        String projectId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // Update project
        mockMvc.perform(put("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateProjectRequest("Renamed Project", "Updated description"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name", is("Renamed Project")));

        // Delete project
        mockMvc.perform(delete("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)));

        // Retrieve deleted project -> 404
        mockMvc.perform(get("/api/v1/projects/" + projectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success", is(false)));
    }

    @Test
    @DisplayName("11. User B cannot access, modify, or delete User A's project")
    void testProjectOwnershipIsolation() throws Exception {
        String tokenAlice = registerAndGetToken("alice_owner@example.com", "Password123!", "Alice");
        String tokenBob = registerAndGetToken("bob_attacker@example.com", "Password123!", "Bob");

        // Alice creates a project
        MvcResult createResult = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + tokenAlice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProjectRequest("Alice's Secret Project", "Confidential mock"))))
                .andExpect(status().isCreated())
                .andReturn();

        String aliceProjectId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // Bob tries to GET Alice's project -> 403 Forbidden
        mockMvc.perform(get("/api/v1/projects/" + aliceProjectId)
                        .header("Authorization", "Bearer " + tokenBob))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success", is(false)));

        // Bob tries to UPDATE Alice's project -> 403 Forbidden
        mockMvc.perform(put("/api/v1/projects/" + aliceProjectId)
                        .header("Authorization", "Bearer " + tokenBob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateProjectRequest("Hacked Project", "Hacked"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success", is(false)));

        // Bob tries to DELETE Alice's project -> 403 Forbidden
        mockMvc.perform(delete("/api/v1/projects/" + aliceProjectId)
                        .header("Authorization", "Bearer " + tokenBob))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success", is(false)));

        // Verify Alice's project is still intact
        mockMvc.perform(get("/api/v1/projects/" + aliceProjectId)
                        .header("Authorization", "Bearer " + tokenAlice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name", is("Alice's Secret Project")));
    }
}
