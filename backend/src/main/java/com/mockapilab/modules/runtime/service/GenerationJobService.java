package com.mockapilab.modules.runtime.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.mockapilab.modules.runtime.messaging.GenerationJobOutboxDispatcher;
import com.mockapilab.modules.runtime.messaging.GenerationJobProducer;
import com.mockapilab.modules.runtime.model.GenerationJob;
import com.mockapilab.modules.runtime.model.GenerationJobOutbox;
import com.mockapilab.modules.runtime.model.GenerationJobStatus;
import com.mockapilab.modules.runtime.model.MockRuntime;
import com.mockapilab.modules.runtime.model.MockRuntimeStatus;
import com.mockapilab.modules.runtime.observability.MockApiLabMetrics;
import com.mockapilab.modules.runtime.repository.GenerationJobOutboxRepository;
import com.mockapilab.modules.runtime.repository.GenerationJobRepository;
import com.mockapilab.modules.runtime.repository.MockRuntimeRepository;
import com.mockapilab.modules.runtime.state.RuntimeStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Service managing the lifecycle, queuing, querying, and asynchronous execution of GenerationJobs.
 */
@Service
public class GenerationJobService {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobService.class);
    private static final int DEFAULT_MAX_OUTBOX_ATTEMPTS = 5;

    private final GenerationJobRepository generationJobRepository;
    private final GenerationJobOutboxRepository outboxRepository;
    private final MockRuntimeRepository runtimeRepository;
    private final ProjectRepository projectRepository;
    private final GenerationJobProducer generationJobProducer;
    private final GenerationJobOutboxDispatcher outboxDispatcher;
    private final MockDataGenerator mockDataGenerator;
    private final RuntimeStateStore stateStore;
    private final ObjectMapper objectMapper;
    private final MockApiLabMetrics metrics;
    private final TransactionTemplate requiredTransactionTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final String generationJobsTopic;
    private final int maxOutboxAttempts;

    public GenerationJobService(
            GenerationJobRepository generationJobRepository,
            MockRuntimeRepository runtimeRepository,
            ProjectRepository projectRepository,
            GenerationJobProducer generationJobProducer,
            MockDataGenerator mockDataGenerator,
            RuntimeStateStore stateStore
    ) {
        this(
                generationJobRepository,
                null,
                runtimeRepository,
                projectRepository,
                generationJobProducer,
                null,
                mockDataGenerator,
                stateStore,
                new ObjectMapper(),
                null,
                null,
                "mockapi.generation.jobs",
                DEFAULT_MAX_OUTBOX_ATTEMPTS
        );
    }

    @Autowired
    public GenerationJobService(
            GenerationJobRepository generationJobRepository,
            GenerationJobOutboxRepository outboxRepository,
            MockRuntimeRepository runtimeRepository,
            ProjectRepository projectRepository,
            GenerationJobProducer generationJobProducer,
            @Lazy GenerationJobOutboxDispatcher outboxDispatcher,
            MockDataGenerator mockDataGenerator,
            RuntimeStateStore stateStore,
            ObjectMapper objectMapper,
            @Autowired(required = false) MockApiLabMetrics metrics,
            @Autowired(required = false) PlatformTransactionManager transactionManager,
            @Value("${mockapilab.kafka.topics.generation-jobs:mockapi.generation.jobs}") String generationJobsTopic,
            @Value("${mockapilab.kafka.outbox.max-attempts:5}") int maxOutboxAttempts
    ) {
        this.generationJobRepository = generationJobRepository;
        this.outboxRepository = outboxRepository;
        this.runtimeRepository = runtimeRepository;
        this.projectRepository = projectRepository;
        this.generationJobProducer = generationJobProducer;
        this.outboxDispatcher = outboxDispatcher;
        this.mockDataGenerator = mockDataGenerator;
        this.stateStore = stateStore;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.metrics = metrics;
        this.generationJobsTopic = generationJobsTopic;
        this.maxOutboxAttempts = Math.max(1, maxOutboxAttempts);
        if (transactionManager != null) {
            this.requiredTransactionTemplate = new TransactionTemplate(transactionManager);
            this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
            this.readOnlyTransactionTemplate.setReadOnly(true);
        } else {
            this.requiredTransactionTemplate = null;
            this.readOnlyTransactionTemplate = null;
        }
    }

    public GenerationJobResponse submitJob(UUID projectId, UUID runtimeId, GenerateDataRequest request, UUID currentUserId) {
        boolean outerTransactionActive = TransactionSynchronizationManager.isActualTransactionActive();

        QueuedJobWithOutbox queued = executeInTransaction(
                requiredTransactionTemplate,
                () -> createAndPersistQueuedJob(projectId, runtimeId, request, currentUserId)
        );

        if (outerTransactionActive) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    recordMetric(GenerationJobStatus.QUEUED);
                    dispatchOutboxBestEffort(queued);
                }
            });
        } else {
            recordMetric(GenerationJobStatus.QUEUED);
            dispatchOutboxBestEffort(queued);
        }

        return GenerationJobResponse.fromEntity(queued.job());
    }

    private QueuedJobWithOutbox createAndPersistQueuedJob(UUID projectId, UUID runtimeId, GenerateDataRequest request, UUID currentUserId) {
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
        long fencingToken = generationJobRepository.nextFencingToken();
        job.setFencingToken(fencingToken);
        job.setStatus(GenerationJobStatus.QUEUED);
        GenerationJob savedJob = generationJobRepository.save(job);

        GenerationJobEvent event = new GenerationJobEvent(
                savedJob.getId(),
                runtimeId,
                projectId,
                normalizedCollection,
                count,
                request.seed(),
                fencingToken
        );

        UUID outboxId = null;
        if (outboxRepository != null) {
            String payload = serializeEvent(event);
            GenerationJobOutbox outbox = new GenerationJobOutbox(
                    savedJob.getId(),
                    generationJobsTopic,
                    savedJob.getId().toString(),
                    payload,
                    maxOutboxAttempts
            );
            outboxId = outboxRepository.save(outbox).getId();
        }

        log.info("Created GenerationJob with id: {} (status: QUEUED) and outboxId: {} for runtimeId: {}, collection: {}, count: {}",
                savedJob.getId(), outboxId, runtimeId, normalizedCollection, count);

        return new QueuedJobWithOutbox(savedJob, outboxId, event);
    }

    private void dispatchOutboxBestEffort(QueuedJobWithOutbox queued) {
        if (outboxDispatcher != null && queued.outboxId() != null) {
            try {
                outboxDispatcher.dispatchOutboxEvent(queued.outboxId());
            } catch (GenerationJobException ex) {
                GenerationJobStatus currentStatus = executeInTransaction(readOnlyTransactionTemplate, () ->
                        generationJobRepository.findById(queued.job().getId())
                                .map(GenerationJob::getStatus)
                                .orElse(null)
                );
                if (currentStatus == GenerationJobStatus.FAILED) {
                    throw ex;
                }
                log.warn("Immediate outbox dispatch deferred to scheduled retry for jobId={}, outboxId={}: {}",
                        queued.job().getId(), queued.outboxId(), ex.getMessage());
            }
            return;
        }

        // Fallback only for unit-test constructor without outbox bean
        try {
            generationJobProducer.sendJob(queued.event());
        } catch (Exception ex) {
            executeInTransaction(requiredTransactionTemplate, () -> {
                GenerationJob persistedJob = generationJobRepository.findById(queued.job().getId()).orElse(queued.job());
                persistedJob.setStatus(GenerationJobStatus.FAILED);
                persistedJob.setErrorMessage(GenerationJobOutboxDispatcher.sanitizeErrorMessage("Failed to dispatch job to Kafka: " + ex.getMessage()));
                persistedJob.setCompletedAt(Instant.now());
                generationJobRepository.save(persistedJob);
                return null;
            });
            recordMetric(GenerationJobStatus.FAILED);
            throw new GenerationJobException("Failed to queue generation job via Kafka: " + ex.getMessage(), ex);
        }
    }

    private String serializeEvent(GenerationJobEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize GenerationJobEvent for outbox persistence", ex);
        }
    }

    private <T> T executeInTransaction(TransactionTemplate template, Supplier<T> action) {
        if (template == null) {
            return action.get();
        }
        return template.execute(status -> action.get());
    }

    private void recordMetric(GenerationJobStatus status) {
        if (metrics != null) {
            metrics.recordGenerationJob(status);
        }
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

    public void processJob(GenerationJobEvent event) {
        log.info("Processing GenerationJobEvent for jobId: {}, runtimeId: {}, collection: {}, count: {}",
                event.jobId(), event.runtimeId(), event.collection(), event.count());

        Integer claimed = executeInTransaction(requiredTransactionTemplate, () ->
                generationJobRepository.claimQueuedJob(event.jobId(), Instant.now())
        );

        if (claimed == null || claimed != 1) {
            log.info("GenerationJob {} could not be claimed (not found, already RUNNING, or terminal). Skipping duplicate/stale execution.",
                    event.jobId());
            return;
        }

        recordMetric(GenerationJobStatus.RUNNING);

        try {
            JobExecutionContext context = executeInTransaction(readOnlyTransactionTemplate, () -> {
                GenerationJob job = generationJobRepository.findById(event.jobId())
                        .orElseThrow(() -> new IllegalStateException("Claimed GenerationJob not found: " + event.jobId()));
                MockRuntime runtime = job.getRuntime();
                NormalizedContract contract = runtime.getContractVersion() != null
                        ? runtime.getContractVersion().getNormalizedDefinition()
                        : null;
                long token = job.getFencingToken() > 0 ? job.getFencingToken() : event.fencingToken();
                return new JobExecutionContext(job.getCollectionPath(), contract, token);
            });

            NormalizedSchema itemSchema = resolveItemSchemaForCollection(context.contract(), context.collectionPath());

            long effectiveSeed = event.requestedSeed() != null
                    ? event.requestedSeed()
                    : (System.currentTimeMillis() ^ (long) (Math.random() * 1000000L));

            List<Map<String, Object>> entities = mockDataGenerator.generateCollection(
                    itemSchema,
                    event.count(),
                    effectiveSeed,
                    context.contract()
            );

            boolean stillRunning = Boolean.TRUE.equals(executeInTransaction(readOnlyTransactionTemplate, () ->
                    generationJobRepository.existsByIdAndStatus(event.jobId(), GenerationJobStatus.RUNNING)
            ));
            if (!stillRunning) {
                log.warn("GenerationJob {} is no longer RUNNING prior to state store write. Aborting stale publish.",
                        event.jobId());
                return;
            }

            if (context.fencingToken() <= 0) {
                throw new IllegalStateException("GenerationJob " + event.jobId() + " has invalid fencingToken=" + context.fencingToken());
            }

            boolean applied = stateStore.initializeCollection(
                    event.runtimeId(),
                    context.collectionPath(),
                    entities,
                    context.fencingToken()
            );
            if (!applied) {
                log.warn("GenerationJob {} rejected by state store due to stale fencingToken={}",
                        event.jobId(), context.fencingToken());
                Integer failedRows = executeInTransaction(requiredTransactionTemplate, () ->
                        generationJobRepository.failRunningJob(
                                event.jobId(),
                                "Superseded by a newer generation job",
                                Instant.now()
                        )
                );
                if (failedRows != null && failedRows == 1) {
                    recordMetric(GenerationJobStatus.FAILED);
                }
                return;
            }

            Integer completedRows = executeInTransaction(requiredTransactionTemplate, () ->
                    generationJobRepository.completeRunningJob(event.jobId(), effectiveSeed, Instant.now())
            );

            if (completedRows != null && completedRows == 1) {
                recordMetric(GenerationJobStatus.COMPLETED);
                log.info("Successfully completed GenerationJob {} for collection '{}': {} entities generated into state store with effective seed {}",
                        event.jobId(), context.collectionPath(), entities.size(), effectiveSeed);
            } else {
                log.warn("GenerationJob {} was not transitioned to COMPLETED (already terminal or recovered externally)",
                        event.jobId());
            }
        } catch (Throwable ex) {
            log.error("GenerationJob {} failed during data generation / state store update: {}",
                    event.jobId(), ex.getMessage(), ex);
            String sanitizedError = GenerationJobOutboxDispatcher.sanitizeErrorMessage(
                    ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()
            );

            Integer failedRows = executeInTransaction(requiredTransactionTemplate, () ->
                    generationJobRepository.failRunningJob(event.jobId(), sanitizedError, Instant.now())
            );

            if (failedRows != null && failedRows == 1) {
                recordMetric(GenerationJobStatus.FAILED);
            } else {
                log.warn("GenerationJob {} was not transitioned to FAILED (already terminal or recovered externally)",
                        event.jobId());
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

    private record QueuedJobWithOutbox(GenerationJob job, UUID outboxId, GenerationJobEvent event) {
    }

    private record JobExecutionContext(String collectionPath, NormalizedContract contract, long fencingToken) {
    }
}