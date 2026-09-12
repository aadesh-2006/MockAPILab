package com.mockapilab.modules.runtime.service;

import com.mockapilab.common.exception.ForbiddenException;
import com.mockapilab.common.exception.ResourceNotFoundException;
import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import com.mockapilab.modules.contract.model.normalized.NormalizedResponse;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
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
import com.mockapilab.modules.runtime.observability.MockApiLabMetrics;
import com.mockapilab.modules.runtime.repository.GenerationJobRepository;
import com.mockapilab.modules.runtime.repository.MockRuntimeRepository;
import com.mockapilab.modules.runtime.state.RuntimeStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service managing the lifecycle, queuing, querying, and asynchronous execution of GenerationJobs.
 */
@Service
public class GenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobService.class);

    private final GenerationJobRepository generationJobRepository;
    private final MockRuntimeRepository runtimeRepository;
    private final ProjectRepository projectRepository;
    private final GenerationJobProducer generationJobProducer;
    private final MockDataGenerator mockDataGenerator;
    private final RuntimeStateStore stateStore;
    private final MockApiLabMetrics metrics;

    public GenerationJobService(
            GenerationJobRepository generationJobRepository,
            MockRuntimeRepository runtimeRepository,
            ProjectRepository projectRepository,
            GenerationJobProducer generationJobProducer,
            MockDataGenerator mockDataGenerator,
            RuntimeStateStore stateStore
    ) {
        this(generationJobRepository, runtimeRepository, projectRepository, generationJobProducer, mockDataGenerator, stateStore, null);
    }

    @Autowired
    public GenerationJobService(
            GenerationJobRepository generationJobRepository,
            MockRuntimeRepository runtimeRepository,
            ProjectRepository projectRepository,
            GenerationJobProducer generationJobProducer,
            MockDataGenerator mockDataGenerator,
            RuntimeStateStore stateStore,
            @Autowired(required = false) MockApiLabMetrics metrics
    ) {
        this.generationJobRepository = generationJobRepository;
        this.runtimeRepository = runtimeRepository;
        this.projectRepository = projectRepository;
        this.generationJobProducer = generationJobProducer;
        this.mockDataGenerator = mockDataGenerator;
        this.stateStore = stateStore;
        this.metrics = metrics;
    }

    @Transactional
    public GenerationJobResponse submitJob(UUID projectId, UUID runtimeId, GenerateDataRequest request, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        MockRuntime runtime = runtimeRepository.findByIdAndProjectId(runtimeId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Runtime not found with id: " + runtimeId));

        if (runtime.getStatus() != MockRuntimeStatus.RUNNING) {
            throw new IllegalStateException("Cannot queue generation job: Runtime is not in RUNNING status (current status: " + runtime.getStatus() + ")");
        }

        String normalizedCollection = normalizeCollectionPath(request.collection());
        int count = request.getEffectiveCount();

        GenerationJob job = new GenerationJob(
                runtime,
                projectId,
                normalizedCollection,
                count,
                request.seed()
        );
        job.setStatus(GenerationJobStatus.QUEUED);
        GenerationJob savedJob = generationJobRepository.save(job);

        if (metrics != null) {
            metrics.recordGenerationJob(GenerationJobStatus.QUEUED);
        }

        log.info("Created GenerationJob with id: {} (status: QUEUED) for runtimeId: {}, collection: {}, count: {}",
                savedJob.getId(), runtimeId, normalizedCollection, count);

        GenerationJobEvent event = new GenerationJobEvent(
                savedJob.getId(),
                runtimeId,
                projectId,
                normalizedCollection,
                count,
                request.seed()
        );

        try {
            generationJobProducer.sendJob(event);
        } catch (Exception ex) {
            log.error("Kafka dispatch failed for GenerationJob {}: {}", savedJob.getId(), ex.getMessage());
            savedJob.setStatus(GenerationJobStatus.FAILED);
            savedJob.setErrorMessage("Failed to dispatch job to Kafka: " + ex.getMessage());
            savedJob.setCompletedAt(Instant.now());
            generationJobRepository.save(savedJob);

            if (metrics != null) {
                metrics.recordGenerationJob(GenerationJobStatus.FAILED);
            }

            throw new GenerationJobException("Failed to queue generation job via Kafka: " + ex.getMessage(), ex);
        }

        return GenerationJobResponse.fromEntity(savedJob);
    }

    @Transactional(readOnly = true)
    public GenerationJobResponse getJob(UUID projectId, UUID runtimeId, UUID jobId, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        GenerationJob job = generationJobRepository.findByIdAndRuntimeId(jobId, runtimeId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation job not found with id: " + jobId));

        return GenerationJobResponse.fromEntity(job);
    }

    @Transactional(readOnly = true)
    public List<GenerationJobResponse> listJobs(UUID projectId, UUID runtimeId, UUID currentUserId) {
        verifyProjectOwnership(projectId, currentUserId);

        runtimeRepository.findByIdAndProjectId(runtimeId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Runtime not found with id: " + runtimeId));

        return generationJobRepository.findByRuntimeIdOrderByCreatedAtDesc(runtimeId).stream()
                .map(GenerationJobResponse::fromEntity)
                .toList();
    }

    @Transactional
    public void processJob(GenerationJobEvent event) {
        log.info("Processing GenerationJobEvent for jobId: {}, runtimeId: {}, collection: {}, count: {}",
                event.jobId(), event.runtimeId(), event.collection(), event.count());

        GenerationJob job = generationJobRepository.findById(event.jobId()).orElse(null);
        if (job == null) {
            log.warn("GenerationJob with id {} not found in database. Skipping processing.", event.jobId());
            return;
        }

        // Idempotency: Ignore already terminal jobs
        if (job.getStatus() == GenerationJobStatus.COMPLETED || job.getStatus() == GenerationJobStatus.FAILED) {
            log.info("GenerationJob {} is already in terminal status {}. Skipping duplicate execution.",
                    job.getId(), job.getStatus());
            return;
        }

        if (job.getStatus() == GenerationJobStatus.RUNNING) {
            log.warn("GenerationJob {} is already RUNNING. Skipping duplicate execution.", job.getId());
            return;
        }

        // Transition to RUNNING
        job.setStatus(GenerationJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job = generationJobRepository.saveAndFlush(job);

        if (metrics != null) {
            metrics.recordGenerationJob(GenerationJobStatus.RUNNING);
        }

        try {
            MockRuntime runtime = job.getRuntime();
            NormalizedContract contract = runtime.getContractVersion() != null
                    ? runtime.getContractVersion().getNormalizedDefinition()
                    : null;
            NormalizedSchema itemSchema = resolveItemSchemaForCollection(contract, job.getCollectionPath());

            long effectiveSeed = event.requestedSeed() != null
                    ? event.requestedSeed()
                    : (System.currentTimeMillis() ^ (long) (Math.random() * 1000000L));

            List<Map<String, Object>> entities = mockDataGenerator.generateCollection(
                    itemSchema,
                    event.count(),
                    effectiveSeed,
                    contract
            );

            stateStore.initializeCollection(event.runtimeId(), job.getCollectionPath(), entities);

            job.setEffectiveSeed(effectiveSeed);
            job.setStatus(GenerationJobStatus.COMPLETED);
            job.setCompletedAt(Instant.now());
            generationJobRepository.save(job);

            if (metrics != null) {
                metrics.recordGenerationJob(GenerationJobStatus.COMPLETED);
            }

            log.info("Successfully completed GenerationJob {} for collection '{}': {} entities generated into state store with effective seed {}",
                    job.getId(), job.getCollectionPath(), entities.size(), effectiveSeed);
        } catch (Throwable ex) {
            log.error("GenerationJob {} failed during data generation / state store update: {}",
                    job.getId(), ex.getMessage(), ex);
            job.setStatus(GenerationJobStatus.FAILED);
            job.setErrorMessage(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            job.setCompletedAt(Instant.now());
            generationJobRepository.save(job);

            if (metrics != null) {
                metrics.recordGenerationJob(GenerationJobStatus.FAILED);
            }
        }
    }

    private NormalizedSchema resolveItemSchemaForCollection(NormalizedContract contract, String collectionPath) {
        if (contract == null) {
            return NormalizedSchema.object(Collections.emptyMap(), Collections.emptyList(), "Generic Object");
        }

        // 1. Check matching endpoints (e.g. POST /pets requestBody or GET /pets response schema)
        if (contract.endpoints() != null) {
            for (NormalizedEndpoint ep : contract.endpoints()) {
                String epPath = normalizeCollectionPath(ep.path());
                if (epPath.equalsIgnoreCase(collectionPath)) {
                    if ("POST".equalsIgnoreCase(ep.method()) && ep.requestBody() != null && ep.requestBody().contentTypes() != null) {
                        for (var mt : ep.requestBody().contentTypes().values()) {
                            if (mt.schema() != null) {
                                return mt.schema();
                            }
                        }
                    }
                    if ("GET".equalsIgnoreCase(ep.method()) && ep.responses() != null) {
                        for (NormalizedResponse resp : ep.responses()) {
                            if (resp.statusCode() != null && resp.statusCode().startsWith("2") && resp.contentTypes() != null) {
                                for (var mt : resp.contentTypes().values()) {
                                    if (mt.schema() != null) {
                                        if ("array".equalsIgnoreCase(mt.schema().type()) && mt.schema().items() != null) {
                                            return mt.schema().items();
                                        }
                                        return mt.schema();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Check schemas map for matching entity name (e.g. /pets -> Pet or pets)
        String singular = collectionPath.replaceAll("^/", "");
        if (singular.endsWith("s") && singular.length() > 1) {
            singular = singular.substring(0, singular.length() - 1);
        }
        if (contract.schemas() != null) {
            for (Map.Entry<String, NormalizedSchema> entry : contract.schemas().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(singular) || entry.getKey().equalsIgnoreCase(collectionPath.replaceAll("^/", ""))) {
                    return entry.getValue();
                }
            }
        }

        return NormalizedSchema.object(Collections.emptyMap(), Collections.emptyList(), "Generic Entity");
    }

    private String normalizeCollectionPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private Project verifyProjectOwnership(UUID projectId, UUID currentUserId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));

        if (!project.getOwner().getId().equals(currentUserId)) {
            throw new ForbiddenException("Access denied: You do not have permission to access project " + projectId);
        }

        return project;
    }
}