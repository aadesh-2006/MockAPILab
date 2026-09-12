package com.mockapilab.common.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.project.dto.CreateProjectRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ErrorResponseRegressionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("401 Unauthorized returns serialized error envelope with timestamp and requestId")
    void testUnauthorizedErrorStructure() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(content);

        assertThat(json.has("timestamp")).isTrue();
        assertThat(Instant.parse(json.get("timestamp").asText())).isNotNull();
        assertThat(json.path("success").asBoolean()).isFalse();

        JsonNode data = json.path("data");
        assertThat(data.path("status").asInt()).isEqualTo(401);
        assertThat(data.path("error").asText()).isEqualTo("Unauthorized");
        assertThat(data.path("message").asText()).isNotEmpty();
        assertThat(data.path("path").asText()).isEqualTo("/api/v1/projects");
        assertThat(data.path("requestId").asText()).isNotEmpty();
        assertThat(data.has("timestamp")).isTrue();
    }

    @Test
    @DisplayName("400 Bad Request on validation error contains timestamp, status, error, message, path, and requestId")
    void testValidationErrorStructure() throws Exception {
        CreateProjectRequest invalidRequest = new CreateProjectRequest("", "");
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(content);

        assertThat(json.has("timestamp")).isTrue();
        assertThat(json.path("data").path("requestId").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("404 Not Found returns serialized error envelope with timestamp, status, error, path, and requestId")
    void testNotFoundErrorStructure() throws Exception {
        MvcResult result = mockMvc.perform(get("/mock/" + UUID.randomUUID() + "/non-existent-route"))
                .andExpect(status().isNotFound())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(content);

        assertThat(json.path("status").asInt()).isEqualTo(404);
        assertThat(json.path("error").asText()).isNotEmpty();
        assertThat(json.path("path").asText()).isNotEmpty();
        assertThat(json.path("requestId").asText()).isNotEmpty();
        assertThat(json.has("timestamp")).isTrue();
    }
}