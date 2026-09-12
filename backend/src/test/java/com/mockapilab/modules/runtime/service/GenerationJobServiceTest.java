package com.mockapilab.modules.runtime.service;

import com.mockapilab.common.exception.ForbiddenException;
import com.mockapilab.common.exception.ResourceNotFoundException;
import com.mockapilab.modules.auth.model.User;
import com.mockapilab.modules.contract.model.Contract;
import com.mockapilab.modules.contract.model.ContractSourceType;
import com.mockapilab.modules.contract.model.ContractVersion;
import com.mockapilab.modules.contract.model.normalized.ContractMetadata;
import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.project.model.Project;
import com.mockapilab.modules.project.repository.ProjectRepository;
import com.mockapilab.modules.runtime.dto.GenerateDataRequest;
import com.mockapilab.modules.runtime.dto.GenerationJobEvent;
import com.mockapilab.modules.runtime.dto.GenerationJobResponse;
import com.mockapilab.modules.runtime.generation.MockDataGenerator;
import com.mockapilab.modules.runtime.messaging.GenerationJobException;
import com.mockapilab.modules.runtime.messaging.GenerationJobProducer;
import com.mockapilab.modules.runtime.model.GenerationJob;
import com.mockapilab.modules.runtime.model.GenerationJobStatus;
import com.mockapilab.modules.runtime.model.MockRuntime;
import com.mockapilab.modules.runtime.model.MockRuntimeStatus;
import com.mockapilab.modules.runtime.repository.GenerationJobRepository;
import com.mockapilab.modules.runtime.repository.MockRuntimeRepository;
import com.mockapilab.modules.runtime.state.RuntimeStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerationJobServiceTest {

    @Mock
    private GenerationJobRepository generationJobRepository;

    @Mock
    private MockRuntimeRepository runtimeRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private GenerationJobProducer generationJobProducer;

    @Mock
    private MockDataGenerator mockDataGenerator;

    @Mock
    private RuntimeStateStore stateStore;

    private GenerationJobService generationJobService;

    private User owner;
    private Project project;
    private MockRuntime runtime;
    private UUID ownerId;
    private UUID projectId;
    private UUID runtimeId;

    @BeforeEach
    void setUp() {
        generationJobService = new GenerationJobService(
                generationJobRepository,
                runtimeRepository,
                projectRepository,
                generationJobProducer,
                mockDataGenerator,
                stateStore
        );

        ownerId = UUID.randomUUID();
        owner = new User("user@test.com", "hash", "Test User");
        owner.setId(ownerId);

        projectId = UUID.randomUUID();
        project = new Project("Test Project", "Desc", owner);
        project.setId(projectId);

        runtimeId = UUID.randomUUID();
        Contract contract = new Contract(project, "Contract", "Contract Desc");
        NormalizedContract normalized = new NormalizedContract(
                new ContractMetadata("API", "1.0", "1.0", "OpenAPI"),
                Collections.emptyList(),
                Collections.emptyMap()
        );
        ContractVersion version = new ContractVersion(contract, 1, ContractSourceType.OPENAPI, normalized);
        runtime = new MockRuntime(project, version, "Runtime", MockRuntimeStatus.RUNNING);
        runtime.setId(runtimeId);
    }

    @Test
    @DisplayName("1. submitJob creates QUEUED job and dispatches Kafka event")
    void testSubmitJobSuccess() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(runtimeRepository.findByIdAndProjectId(runtimeId, projectId)).thenReturn(Optional.of(runtime));
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(invocation -> {
            GenerationJob j = invocation.getArgument(0);
            if (j.getId() == null) {
                j.setId(UUID.randomUUID());
            }
            return j;
        });

        GenerateDataRequest request = new GenerateDataRequest("/users", 5, 42L);
        GenerationJobResponse response = generationJobService.submitJob(projectId, runtimeId, request, ownerId);

        assertThat(response).isNotNull();
        assertThat(response.jobId()).isNotNull();
        assertThat(response.status()).isEqualTo(GenerationJobStatus.QUEUED);
        assertThat(response.collection()).isEqualTo("/users");
        assertThat(response.count()).isEqualTo(5);
        assertThat(response.requestedSeed()).isEqualTo(42L);

        ArgumentCaptor<GenerationJobEvent> eventCaptor = ArgumentCaptor.forClass(GenerationJobEvent.class);
        verify(generationJobProducer).sendJob(eventCaptor.capture());

        GenerationJobEvent sentEvent = eventCaptor.getValue();
        assertThat(sentEvent.jobId()).isEqualTo(response.jobId());
        assertThat(sentEvent.runtimeId()).isEqualTo(runtimeId);
        assertThat(sentEvent.collection()).isEqualTo("/users");
        assertThat(sentEvent.count()).isEqualTo(5);
        assertThat(sentEvent.requestedSeed()).isEqualTo(42L);
    }

    @Test
    @DisplayName("2. submitJob rejects stopped runtime")
    void testSubmitJobStoppedRuntime() {
        runtime.setStatus(MockRuntimeStatus.STOPPED);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(runtimeRepository.findByIdAndProjectId(runtimeId, projectId)).thenReturn(Optional.of(runtime));

        GenerateDataRequest request = new GenerateDataRequest("/users", 5, 42L);
        assertThatThrownBy(() -> generationJobService.submitJob(projectId, runtimeId, request, ownerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Runtime is not in RUNNING status");

        verify(generationJobProducer, never()).sendJob(any());
    }

    @Test
    @DisplayName("3. submitJob rejects unauthorized project access")
    void testSubmitJobUnauthorized() {
        UUID foreignUserId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        GenerateDataRequest request = new GenerateDataRequest("/users", 5, 42L);
        assertThatThrownBy(() -> generationJobService.submitJob(projectId, runtimeId, request, foreignUserId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied");

        verify(generationJobProducer, never()).sendJob(any());
    }

    @Test
    @DisplayName("4. submitJob marks job FAILED when Kafka publish throws exception")
    void testSubmitJobKafkaFailure() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(runtimeRepository.findByIdAndProjectId(runtimeId, projectId)).thenReturn(Optional.of(runtime));
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(invocation -> {
            GenerationJob j = invocation.getArgument(0);
            if (j.getId() == null) {
                j.setId(UUID.randomUUID());
            }
            return j;
        });
        doThrow(new RuntimeException("Kafka connection broker timeout"))
                .when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        GenerateDataRequest request = new GenerateDataRequest("/users", 5, 42L);
        assertThatThrownBy(() -> generationJobService.submitJob(projectId, runtimeId, request, ownerId))
                .isInstanceOf(GenerationJobException.class)
                .hasMessageContaining("Failed to queue generation job via Kafka");

        ArgumentCaptor<GenerationJob> jobCaptor = ArgumentCaptor.forClass(GenerationJob.class);
        verify(generationJobRepository, times(2)).save(jobCaptor.capture());
        GenerationJob lastSaved = jobCaptor.getAllValues().get(1);
        assertThat(lastSaved.getStatus()).isEqualTo(GenerationJobStatus.FAILED);
        assertThat(lastSaved.getErrorMessage()).contains("Kafka connection broker timeout");
        assertThat(lastSaved.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("5. processJob executes generation and completes job")
    void testProcessJobSuccess() {
        UUID jobId = UUID.randomUUID();
        GenerationJob job = new GenerationJob(runtime, projectId, "/users", 3, 100L);
        job.setId(jobId);
        job.setStatus(GenerationJobStatus.QUEUED);

        when(generationJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(generationJobRepository.saveAndFlush(any(GenerationJob.class))).thenAnswer(i -> i.getArgument(0));
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(i -> i.getArgument(0));

        List<Map<String, Object>> mockEntities = List.of(
                Map.of("id", "1", "name", "Alice"),
                Map.of("id", "2", "name", "Bob"),
                Map.of("id", "3", "name", "Charlie")
        );
        when(mockDataGenerator.generateCollection(any(), eq(3), eq(100L), any())).thenReturn(mockEntities);

        GenerationJobEvent event = new GenerationJobEvent(jobId, runtimeId, projectId, "/users", 3, 100L);
        generationJobService.processJob(event);

        assertThat(job.getStatus()).isEqualTo(GenerationJobStatus.COMPLETED);
        assertThat(job.getEffectiveSeed()).isEqualTo(100L);
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(job.getCompletedAt()).isNotNull();

        verify(stateStore).initializeCollection(runtimeId, "/users", mockEntities);
    }

    @Test
    @DisplayName("6. processJob idempotency: ignores already COMPLETED job")
    void testProcessJobIdempotencyCompleted() {
        UUID jobId = UUID.randomUUID();
        GenerationJob job = new GenerationJob(runtime, projectId, "/users", 3, 100L);
        job.setId(jobId);
        job.setStatus(GenerationJobStatus.COMPLETED);

        when(generationJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        GenerationJobEvent event = new GenerationJobEvent(jobId, runtimeId, projectId, "/users", 3, 100L);
        generationJobService.processJob(event);

        verify(mockDataGenerator, never()).generateCollection(any(), anyInt(), anyLong(), any());
        verify(stateStore, never()).initializeCollection(any(), any(), any());
    }

    @Test
    @DisplayName("7. processJob idempotency: ignores already FAILED job")
    void testProcessJobIdempotencyFailed() {
        UUID jobId = UUID.randomUUID();
        GenerationJob job = new GenerationJob(runtime, projectId, "/users", 3, 100L);
        job.setId(jobId);
        job.setStatus(GenerationJobStatus.FAILED);

        when(generationJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        GenerationJobEvent event = new GenerationJobEvent(jobId, runtimeId, projectId, "/users", 3, 100L);
        generationJobService.processJob(event);

        verify(mockDataGenerator, never()).generateCollection(any(), anyInt(), anyLong(), any());
        verify(stateStore, never()).initializeCollection(any(), any(), any());
    }

    @Test
    @DisplayName("8. processJob handles generation error and marks job FAILED")
    void testProcessJobErrorHandling() {
        UUID jobId = UUID.randomUUID();
        GenerationJob job = new GenerationJob(runtime, projectId, "/users", 3, 100L);
        job.setId(jobId);
        job.setStatus(GenerationJobStatus.QUEUED);

        when(generationJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(generationJobRepository.saveAndFlush(any(GenerationJob.class))).thenAnswer(i -> i.getArgument(0));
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(i -> i.getArgument(0));

        when(mockDataGenerator.generateCollection(any(), eq(3), eq(100L), any()))
                .thenThrow(new RuntimeException("Schema evaluation cycle detected"));

        GenerationJobEvent event = new GenerationJobEvent(jobId, runtimeId, projectId, "/users", 3, 100L);
        generationJobService.processJob(event);

        assertThat(job.getStatus()).isEqualTo(GenerationJobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("Schema evaluation cycle detected");
        assertThat(job.getCompletedAt()).isNotNull();

        verify(stateStore, never()).initializeCollection(any(), any(), any());
    }
}