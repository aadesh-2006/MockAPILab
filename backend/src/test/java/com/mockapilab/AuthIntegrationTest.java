package com.mockapilab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.auth.dto.LoginRequest;
import com.mockapilab.modules.auth.dto.RegisterRequest;
import com.mockapilab.modules.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("1. Successful user registration")
    void testSuccessfulRegistration() throws Exception {
        RegisterRequest request = new RegisterRequest("alice@example.com", "SecurePassword123!", "Alice Developer");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.message", is("User registered successfully")))
                .andExpect(jsonPath("$.data.token", notNullValue()))
                .andExpect(jsonPath("$.data.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.data.user.email", is("alice@example.com")))
                .andExpect(jsonPath("$.data.user.displayName", is("Alice Developer")))
                .andExpect(jsonPath("$.data.user.id", notNullValue()))
                .andExpect(jsonPath("$.data.user.password").doesNotExist())
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("2. Duplicate email registration rejected with 409 Conflict")
    void testDuplicateEmailRegistration() throws Exception {
        RegisterRequest request = new RegisterRequest("bob@example.com", "Password123!", "Bob Engineer");

        // First registration succeeds
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Duplicate registration must fail with 409
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.message", containsString("already exists")));
    }

    @Test
    @DisplayName("3. Invalid registration input rejected with 400 Bad Request")
    void testInvalidRegistrationRejected() throws Exception {
        // Invalid email, short password, empty display name
        RegisterRequest request = new RegisterRequest("not-an-email", "short", "");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.message", is("Validation failed")))
                .andExpect(jsonPath("$.data.email", notNullValue()))
                .andExpect(jsonPath("$.data.password", notNullValue()))
                .andExpect(jsonPath("$.data.displayName", notNullValue()));
    }

    @Test
    @DisplayName("4. Successful user login")
    void testSuccessfulLogin() throws Exception {
        RegisterRequest registerReq = new RegisterRequest("carol@example.com", "ValidPass123!", "Carol Architect");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isCreated());

        LoginRequest loginReq = new LoginRequest("carol@example.com", "ValidPass123!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.token", notNullValue()))
                .andExpect(jsonPath("$.data.user.email", is("carol@example.com")));
    }

    @Test
    @DisplayName("5. Invalid login credentials rejected with 401 Unauthorized")
    void testInvalidLoginRejected() throws Exception {
        RegisterRequest registerReq = new RegisterRequest("dave@example.com", "CorrectPassword123!", "Dave Lead");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isCreated());

        // Wrong password
        LoginRequest wrongPass = new LoginRequest("dave@example.com", "WrongPassword!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongPass)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success", is(false)));

        // Non-existent user
        LoginRequest nonExistent = new LoginRequest("nobody@example.com", "SomePassword!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nonExistent)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success", is(false)));
    }

    @Test
    @DisplayName("6. Protected endpoint rejects unauthenticated request with 401 Unauthorized")
    void testUnauthenticatedRequestRejected() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success", is(false)));
    }

    @Test
    @DisplayName("12. Invalid JWT token rejected with 401 Unauthorized")
    void testInvalidJwtRejected() throws Exception {
        mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer invalid.fake.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success", is(false)));
    }
}
