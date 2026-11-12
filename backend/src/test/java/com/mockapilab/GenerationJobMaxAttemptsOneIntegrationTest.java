package com.mockapilab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.runtime.dto.GenerateDataRequest;
import com.mockapilab.modules.runtime.dto.GenerationJobEvent;
import com.mockapilab.modules.runtime.dto.StartRuntimeRequest;
import com.mockapilab.modules.runtime.messaging.GenerationJobException;
import com.mockapilab.modules.runtime.messaging.GenerationJobOutboxDispatcher;
import com.mockapilab.modules.runtime.messaging.GenerationJobProducer;
import com.mockapilab.modules.runtime.model.GenerationJob;
import com.mockapilab.modules.runtime.model.GenerationJobOutbox;
import com.mockapilab.modules.runtime.model.GenerationJobOutboxStatus;
import com.mockapilab.modules.runtime.model.GenerationJobStatus;
import com.mockapilab.modules.runtime.repository.GenerationJobOutboxRepository;
import com.mockapilab.modules.runtime.repository.GenerationJobRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "mockapilab.kafka.outbox.max-attempts=1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GenerationJobMaxAttemptsOneIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GenerationJobRepository generationJobRepository;

    @Autowired
    private GenerationJobOutboxRepository outboxRepository;

    @MockBean
    private GenerationJobProducer generationJobProducer;

    @SuppressWarnings("unused")
    @Autowired
    private GenerationJobOutboxDispatcher outboxDispatcher;

    private static final String PET_STORE_OPENAPI = """
            openapi: 3.0.3
            info:
              title: MaxAttemptsOne Pet Store
              version: 1.0.0
            paths:
              /pets:
                get:
                  responses:
                    '200':
                      description: List
            """;

    private String token;
    private UUID projectId;
    private UUID runtimeId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "max1-" + UUID.randomUUID() + "@test.com";
        MvcResult regRes = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"email\":\"%s\",\"password\":\"Password123!\",\"displayName\":\"Max1\"}", email)))
                .andExpect(status().isCreated())
                .andReturn();
        token = objectMapper.readTree(regRes.getResponse().getContentAsString()).path("data").path("token").asText();

        MvcResult projRes = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Max1 Project\",\"description\":\"Desc\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        projectId = UUID.fromString(objectMapper.readTree(projRes.getResponse().getContentAsString()).path("data").path("id").asText());

        String contractBody = objectMapper.writeValueAsString(new IngestContractDto("Max1 Contract", PET_STORE_OPENAPI, "OPENAPI"));
        MvcResult contractRes = mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contractBody))
                .andExpect(status().isCreated())
                .andReturn();
        String contractId = objectMapper.readTree(contractRes.getResponse().getContentAsString()).path("data").path("id").asText();

        MvcResult runtimeRes = mockMvc.perform(post("/api/v1/projects/" + projectId + "/contracts/" + contractId + "/versions/1/runtime")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartRuntimeRequest("Max1 Runtime"))))
                .andExpect(status().isCreated())
                .andReturn();
        runtimeId = UUID.fromString(objectMapper.readTree(runtimeRes.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    @Test
    @DisplayName("With maxAttempts=1, immediate Kafka failure returns 503 and persists FAILED job and outbox")
    void testMaxAttemptsOneImmediateFailureReturns503AndPersistsFailed() throws Exception {
        doThrow(new GenerationJobException("Kafka down"))
                .when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/data/generate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GenerateDataRequest("/pets", 3, 1L))))
                .andExpect(status().isServiceUnavailable());

        List<GenerationJob> jobs = generationJobRepository.findByRuntimeIdOrderByCreatedAtDesc(runtimeId);
        assertThat(jobs).hasSize(1);
        GenerationJob failedJob = jobs.get(0);
        assertThat(failedJob.getStatus()).isEqualTo(GenerationJobStatus.FAILED);
        assertThat(failedJob.getCompletedAt()).isNotNull();

        GenerationJobOutbox failedOutbox = outboxRepository.findByJobId(failedJob.getId()).orElseThrow();
        assertThat(failedOutbox.getStatus()).isEqualTo(GenerationJobOutboxStatus.FAILED);
        assertThat(failedOutbox.getAttemptCount()).isEqualTo(1);
    }

    private record IngestContractDto(String name, String content, String sourceType) {}
}
