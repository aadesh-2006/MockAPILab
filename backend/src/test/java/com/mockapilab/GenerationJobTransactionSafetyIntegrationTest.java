package com.mockapilab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.runtime.dto.GenerateDataRequest;
import com.mockapilab.modules.runtime.dto.GenerationJobEvent;
import com.mockapilab.modules.runtime.dto.GenerationJobResponse;
import com.mockapilab.modules.runtime.dto.StartRuntimeRequest;
import com.mockapilab.modules.runtime.generation.MockDataGenerator;
import com.mockapilab.modules.runtime.messaging.GenerationJobConsumer;
import com.mockapilab.modules.runtime.messaging.GenerationJobException;
import com.mockapilab.modules.runtime.messaging.GenerationJobOutboxDispatcher;
import com.mockapilab.modules.runtime.messaging.GenerationJobProducer;
import com.mockapilab.modules.runtime.model.GenerationJob;
import com.mockapilab.modules.runtime.model.GenerationJobOutbox;
import com.mockapilab.modules.runtime.model.GenerationJobOutboxStatus;
import com.mockapilab.modules.runtime.model.GenerationJobStatus;
import com.mockapilab.modules.runtime.repository.GenerationJobOutboxRepository;
import com.mockapilab.modules.runtime.repository.GenerationJobRepository;
import com.mockapilab.modules.runtime.repository.MockRuntimeRepository;
import com.mockapilab.modules.runtime.service.GenerationJobService;
import com.mockapilab.modules.runtime.state.RuntimeStateStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GenerationJobTransactionSafetyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GenerationJobService generationJobService;

    @Autowired
    private com.mockapilab.modules.runtime.service.RuntimeService runtimeService;

    @Autowired
    private GenerationJobConsumer generationJobConsumer;

    @Autowired
    private GenerationJobOutboxDispatcher outboxDispatcher;

    @Autowired
    private GenerationJobRepository generationJobRepository;

    @Autowired
    private GenerationJobOutboxRepository outboxRepository;

    @Autowired
    private MockRuntimeRepository runtimeRepository;

    @Autowired
    private RuntimeStateStore stateStore;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockBean
    private GenerationJobProducer generationJobProducer;

    @SpyBean
    private MockDataGenerator mockDataGenerator;

    private static final String PET_STORE_OPENAPI = """
            openapi: 3.0.3
            info:
              title: Transaction Safety Pet Store
              version: 1.0.0
            paths:
              /pets:
                get:
                  summary: List pets
                  responses:
                    '200':
                      description: List of pets
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              $ref: '#/components/schemas/Pet'
            components:
              schemas:
                Pet:
                  type: object
                  required:
                    - id
                    - name
                  properties:
                    id:
                      type: integer
                      format: int64
                    name:
                      type: string
            """;

    private String token;
    private UUID userId;
    private UUID projectId;
    private UUID runtimeId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "tx-safety-" + UUID.randomUUID() + "@test.com";
        String regBody = String.format("{\"email\":\"%s\",\"password\":\"Password123!\",\"displayName\":\"TX Tester\"}", email);
        MvcResult regRes = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regBody))
                .andExpect(status().isCreated())
                .andReturn();
        var regJson = objectMapper.readTree(regRes.getResponse().getContentAsString()).path("data");
        token = regJson.path("token").asText();
        userId = UUID.fromString(regJson.path("user").path("id").asText());

        MvcResult projRes = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TX Safety Project\",\"description\":\"Desc\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        projectId = UUID.fromString(objectMapper.readTree(projRes.getResponse().getContentAsString()).path("data").path("id").asText());

        String contractBody = objectMapper.writeValueAsString(new IngestContractDto("TX Pet Contract", PET_STORE_OPENAPI, "OPENAPI"));
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
                        .content(objectMapper.writeValueAsString(new StartRuntimeRequest("TX Runtime"))))
                .andExpect(status().isCreated())
                .andReturn();
        runtimeId = UUID.fromString(objectMapper.readTree(runtimeRes.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    @Test
    @DisplayName("1. Job and outbox commit atomically; event published post-commit; duplicate delivery is idempotent")
    void testJobAndOutboxCommitAtomicallyAndConsumerIsIdempotent() {
        TransactionTemplate requiresNewTx = new TransactionTemplate(transactionManager);
        requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        AtomicReference<Boolean> txActiveDuringPublish = new AtomicReference<>();
        AtomicReference<GenerationJobStatus> statusVisibleToConsumer = new AtomicReference<>();
        AtomicReference<GenerationJobEvent> capturedEvent = new AtomicReference<>();

        double queuedBefore = getJobMetricCount(GenerationJobStatus.QUEUED);
        double completedBefore = getJobMetricCount(GenerationJobStatus.COMPLETED);

        doAnswer(invocation -> {
            GenerationJobEvent event = invocation.getArgument(0);
            capturedEvent.set(event);
            txActiveDuringPublish.set(TransactionSynchronizationManager.isActualTransactionActive());

            GenerationJob committedJob = requiresNewTx.execute(status ->
                    generationJobRepository.findById(event.jobId()).orElse(null)
            );
            GenerationJobOutbox committedOutbox = requiresNewTx.execute(status ->
                    outboxRepository.findByJobId(event.jobId()).orElse(null)
            );
            assertThat(committedJob).isNotNull();
            assertThat(committedOutbox).isNotNull();
            statusVisibleToConsumer.set(committedJob.getStatus());

            generationJobConsumer.consumeGenerationJob(event);
            return null;
        }).when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        GenerationJobResponse response = generationJobService.submitJob(
                projectId,
                runtimeId,
                new GenerateDataRequest("/pets", 4, 99L),
                userId
        );

        assertThat(txActiveDuringPublish.get()).isFalse();
        assertThat(statusVisibleToConsumer.get()).isEqualTo(GenerationJobStatus.QUEUED);
        assertThat(response.status()).isEqualTo(GenerationJobStatus.QUEUED);

        GenerationJob completedJob = generationJobRepository.findById(response.jobId()).orElseThrow();
        assertThat(completedJob.getStatus()).isEqualTo(GenerationJobStatus.COMPLETED);
        assertThat(completedJob.getEffectiveSeed()).isEqualTo(99L);

        GenerationJobOutbox publishedOutbox = outboxRepository.findByJobId(response.jobId()).orElseThrow();
        assertThat(publishedOutbox.getStatus()).isEqualTo(GenerationJobOutboxStatus.PUBLISHED);
        assertThat(publishedOutbox.getPublishedAt()).isNotNull();

        assertThat(getJobMetricCount(GenerationJobStatus.QUEUED)).isEqualTo(queuedBefore + 1.0);
        assertThat(getJobMetricCount(GenerationJobStatus.COMPLETED)).isEqualTo(completedBefore + 1.0);

        // Duplicate delivery must not regenerate data
        generationJobConsumer.consumeGenerationJob(capturedEvent.get());
        assertThat(stateStore.getCollection(runtimeId, "/pets")).hasSize(4);
        verify(generationJobProducer, times(1)).sendJob(any(GenerationJobEvent.class));
    }

    @Test
    @DisplayName("2. Rolled-back transaction creates neither durable job, outbox event, Kafka publish, nor metric")
    void testRolledBackTransactionCreatesNoJobOutboxOrMetric() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        double queuedBefore = getJobMetricCount(GenerationJobStatus.QUEUED);
        long outboxCountBefore = outboxRepository.count();

        txTemplate.executeWithoutResult(status -> {
            generationJobService.submitJob(
                    projectId,
                    runtimeId,
                    new GenerateDataRequest("/pets", 3, 12L),
                    userId
            );
            status.setRollbackOnly();
        });

        verify(generationJobProducer, never()).sendJob(any(GenerationJobEvent.class));
        assertThat(generationJobRepository.findByRuntimeIdOrderByCreatedAtDesc(runtimeId)).isEmpty();
        assertThat(outboxRepository.count()).isEqualTo(outboxCountBefore);
        assertThat(getJobMetricCount(GenerationJobStatus.QUEUED)).isEqualTo(queuedBefore);
    }

    @Test
    @DisplayName("3. Scheduled dispatcher publishes pending outbox events left behind after crash/restart")
    void testDispatcherPublishesPendingOutboxAfterRestart() throws Exception {
        var runtime = runtimeRepository.findById(runtimeId).orElseThrow();
        GenerationJob job = generationJobRepository.save(new GenerationJob(runtime, projectId, "/pets", 2, 7L));
        GenerationJobEvent event = new GenerationJobEvent(job.getId(), runtimeId, projectId, "/pets", 2, 7L);
        GenerationJobOutbox outbox = outboxRepository.save(new GenerationJobOutbox(
                job.getId(),
                "mockapi.generation.jobs.test",
                job.getId().toString(),
                objectMapper.writeValueAsString(event),
                5
        ));

        doNothing().when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        outboxDispatcher.dispatchPendingOutboxEvents();

        verify(generationJobProducer, times(1)).sendJob(event);
        GenerationJobOutbox reloaded = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GenerationJobOutboxStatus.PUBLISHED);
        assertThat(reloaded.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("4. Kafka failure retains retryable PENDING outbox event with sanitized error and recovers on retry")
    void testKafkaFailureRetainsRetryableOutboxEvent() throws Exception {
        doThrow(new GenerationJobException("Broker unavailable password=supersecret", new RuntimeException("timeout")))
                .when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        GenerateDataRequest request = new GenerateDataRequest("/pets", 5, 42L);
        MvcResult submitRes = mockMvc.perform(post("/api/v1/projects/" + projectId + "/runtimes/" + runtimeId + "/data/generate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status", is("QUEUED")))
                .andReturn();

        UUID jobId = UUID.fromString(objectMapper.readTree(submitRes.getResponse().getContentAsString())
                .path("data").path("jobId").asText());

        GenerationJobOutbox outbox = outboxRepository.findByJobId(jobId).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(GenerationJobOutboxStatus.PENDING);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));
        assertThat(outbox.getLastError()).contains("[REDACTED]").doesNotContain("supersecret");

        // Now Kafka recovers: force nextAttemptAt to past and dispatch
        outbox.setNextAttemptAt(Instant.now().minusSeconds(1));
        outboxRepository.save(outbox);

        doNothing().when(generationJobProducer).sendJob(any(GenerationJobEvent.class));
        outboxDispatcher.dispatchPendingOutboxEvents();

        GenerationJobOutbox published = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(GenerationJobOutboxStatus.PUBLISHED);
    }

    @Test
    @DisplayName("5. Multiple concurrent dispatchers cannot claim the same outbox event")
    void testMultipleDispatchersCannotClaimSameEventConcurrently() throws Exception {
        var runtime = runtimeRepository.findById(runtimeId).orElseThrow();
        GenerationJob job = generationJobRepository.save(new GenerationJob(runtime, projectId, "/pets", 2, 11L));
        GenerationJobEvent event = new GenerationJobEvent(job.getId(), runtimeId, projectId, "/pets", 2, 11L);
        GenerationJobOutbox outbox = outboxRepository.save(new GenerationJobOutbox(
                job.getId(),
                "mockapi.generation.jobs.test",
                job.getId().toString(),
                objectMapper.writeValueAsString(event),
                5
        ));

        AtomicInteger publishCount = new AtomicInteger(0);
        doAnswer(inv -> {
            publishCount.incrementAndGet();
            Thread.sleep(50);
            return null;
        }).when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                startGate.await();
                return outboxDispatcher.dispatchOutboxEvent(outbox.getId());
            });
        }

        List<Future<Boolean>> futures = new ArrayList<>();
        for (Callable<Boolean> task : tasks) {
            futures.add(pool.submit(task));
        }
        startGate.countDown();

        int claimedWins = 0;
        for (Future<Boolean> f : futures) {
            if (Boolean.TRUE.equals(f.get())) {
                claimedWins++;
            }
        }
        pool.shutdown();

        assertThat(claimedWins).isEqualTo(1);
        assertThat(publishCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("6. Concurrent consumers cannot claim and execute the same QUEUED job simultaneously")
    void testConcurrentConsumersCannotClaimSameJobSimultaneously() throws Exception {
        var runtime = runtimeRepository.findById(runtimeId).orElseThrow();
        long token = generationJobRepository.nextFencingToken();
        GenerationJob newJob = new GenerationJob(runtime, projectId, "/pets", 3, 55L);
        newJob.setFencingToken(token);
        GenerationJob job = generationJobRepository.save(newJob);
        GenerationJobEvent event = new GenerationJobEvent(job.getId(), runtimeId, projectId, "/pets", 3, 55L, token);

        double runningBefore = getJobMetricCount(GenerationJobStatus.RUNNING);
        double completedBefore = getJobMetricCount(GenerationJobStatus.COMPLETED);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                generationJobConsumer.consumeGenerationJob(event);
                return null;
            }));
        }
        startGate.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        GenerationJob completed = generationJobRepository.findById(job.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(GenerationJobStatus.COMPLETED);
        assertThat(stateStore.getCollection(runtimeId, "/pets")).hasSize(3);
        assertThat(getJobMetricCount(GenerationJobStatus.RUNNING)).isEqualTo(runningBefore + 1.0);
        assertThat(getJobMetricCount(GenerationJobStatus.COMPLETED)).isEqualTo(completedBefore + 1.0);
    }

    @Test
    @DisplayName("7. Stale RUNNING jobs left after worker crash are safely recovered to FAILED")
    void testStaleRunningJobsRecoveredToFailed() {
        var runtime = runtimeRepository.findById(runtimeId).orElseThrow();
        GenerationJob crashedJob = new GenerationJob(runtime, projectId, "/pets", 3, 77L);
        crashedJob.setStatus(GenerationJobStatus.RUNNING);
        crashedJob.setStartedAt(Instant.now().minusSeconds(600));
        crashedJob = generationJobRepository.save(crashedJob);

        double failedBefore = getJobMetricCount(GenerationJobStatus.FAILED);
        int recovered = outboxDispatcher.recoverStaleRunningJobs(Duration.ofSeconds(60));

        assertThat(recovered).isEqualTo(1);
        GenerationJob reloaded = generationJobRepository.findById(crashedJob.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GenerationJobStatus.FAILED);
        assertThat(reloaded.getCompletedAt()).isNotNull();
        assertThat(reloaded.getErrorMessage()).contains("timed out or terminated unexpectedly");
        assertThat(getJobMetricCount(GenerationJobStatus.FAILED)).isEqualTo(failedBefore + 1.0);
    }

    private double getJobMetricCount(GenerationJobStatus status) {
        Counter counter = meterRegistry.find("mockapilab.generation.jobs.total")
                .tag("status", status.name())
                .counter();
        return counter != null ? counter.count() : 0.0;
    }

    @Test
    @DisplayName("8. Stale recovery race: slow worker finishing after recovery cannot overwrite FAILED with COMPLETED or increment COMPLETED metric")
    void testSlowWorkerCannotOverwriteRecoveredFailedWithCompleted() {
        var runtime = runtimeRepository.findById(runtimeId).orElseThrow();
        GenerationJob job = generationJobRepository.save(new GenerationJob(runtime, projectId, "/pets", 2, 101L));
        GenerationJobEvent event = new GenerationJobEvent(job.getId(), runtimeId, projectId, "/pets", 2, 101L);

        double completedBefore = getJobMetricCount(GenerationJobStatus.COMPLETED);
        double failedBefore = getJobMetricCount(GenerationJobStatus.FAILED);

        doAnswer(invocation -> {
            // Simulate stale job recovery running while this worker is still executing
            int recovered = outboxDispatcher.recoverStaleRunningJobs(Duration.ZERO);
            assertThat(recovered).isGreaterThanOrEqualTo(1);
            return invocation.callRealMethod();
        }).when(mockDataGenerator).generateCollection(any(), any(Integer.class), any(Long.class), any());

        try {
            generationJobService.processJob(event);
        } finally {
            org.mockito.Mockito.reset(mockDataGenerator);
        }

        GenerationJob persisted = generationJobRepository.findById(job.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(GenerationJobStatus.FAILED);
        assertThat(persisted.getErrorMessage()).contains("timed out or terminated unexpectedly");
        assertThat(getJobMetricCount(GenerationJobStatus.COMPLETED)).isEqualTo(completedBefore);
        assertThat(getJobMetricCount(GenerationJobStatus.FAILED)).isEqualTo(failedBefore + 1.0);
    }

    @Test
    @DisplayName("9. Stale recovery race: slow worker failing after terminal transition cannot overwrite existing terminal state or double-count FAILED")
    void testSlowWorkerFailureCannotOverwriteTerminalState() {
        var runtime = runtimeRepository.findById(runtimeId).orElseThrow();
        GenerationJob job = generationJobRepository.save(new GenerationJob(runtime, projectId, "/pets", 2, 102L));
        GenerationJobEvent event = new GenerationJobEvent(job.getId(), runtimeId, projectId, "/pets", 2, 102L);

        double failedBefore = getJobMetricCount(GenerationJobStatus.FAILED);

        doAnswer(invocation -> {
            // Simulate stale job recovery marking FAILED while worker is mid-flight, then worker throws
            outboxDispatcher.recoverStaleRunningJobs(Duration.ZERO);
            throw new RuntimeException("Late worker failure");
        }).when(mockDataGenerator).generateCollection(any(), any(Integer.class), any(Long.class), any());

        try {
            generationJobService.processJob(event);
        } finally {
            org.mockito.Mockito.reset(mockDataGenerator);
        }

        GenerationJob persisted = generationJobRepository.findById(job.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(GenerationJobStatus.FAILED);
        assertThat(persisted.getErrorMessage()).contains("timed out or terminated unexpectedly");
        assertThat(persisted.getErrorMessage()).doesNotContain("Late worker failure");
        assertThat(getJobMetricCount(GenerationJobStatus.FAILED)).isEqualTo(failedBefore + 1.0);
    }

    @Test
    @DisplayName("10. Expired PROCESSING outbox lease is reclaimed and republished")
    void testExpiredProcessingLeaseReclaimedAndRepublished() throws Exception {
        var runtime = runtimeRepository.findById(runtimeId).orElseThrow();
        GenerationJob job = generationJobRepository.save(new GenerationJob(runtime, projectId, "/pets", 2, 103L));
        GenerationJobEvent event = new GenerationJobEvent(job.getId(), runtimeId, projectId, "/pets", 2, 103L);

        GenerationJobOutbox staleProcessing = new GenerationJobOutbox(
                job.getId(),
                "mockapi.generation.jobs.test",
                job.getId().toString(),
                objectMapper.writeValueAsString(event),
                5
        );
        staleProcessing.setStatus(GenerationJobOutboxStatus.PROCESSING);
        staleProcessing.setLockedBy("dead-pod-1");
        staleProcessing.setLockedAt(Instant.now().minusSeconds(120));
        staleProcessing.setNextAttemptAt(Instant.now().minusSeconds(120));
        staleProcessing = outboxRepository.save(staleProcessing);

        doNothing().when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        outboxDispatcher.dispatchPendingOutboxEvents();

        verify(generationJobProducer, times(1)).sendJob(event);
        GenerationJobOutbox published = outboxRepository.findById(staleProcessing.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(GenerationJobOutboxStatus.PUBLISHED);
        assertThat(published.getLockedBy()).isNull();
        assertThat(published.getLockedAt()).isNull();
    }

    @Test
    @DisplayName("11. Retry exhaustion at attemptCount == maxAttempts marks both outbox and QUEUED job FAILED")
    void testRetryExhaustionMarksOutboxAndJobFailed() throws Exception {
        var runtime = runtimeRepository.findById(runtimeId).orElseThrow();
        GenerationJob job = generationJobRepository.save(new GenerationJob(runtime, projectId, "/pets", 2, 104L));
        GenerationJobEvent event = new GenerationJobEvent(job.getId(), runtimeId, projectId, "/pets", 2, 104L);

        GenerationJobOutbox outbox = new GenerationJobOutbox(
                job.getId(),
                "mockapi.generation.jobs.test",
                job.getId().toString(),
                objectMapper.writeValueAsString(event),
                5
        );
        outbox.setAttemptCount(4);
        outbox.setNextAttemptAt(Instant.now().minusSeconds(1));
        outbox = outboxRepository.save(outbox);

        double failedBefore = getJobMetricCount(GenerationJobStatus.FAILED);
        doThrow(new GenerationJobException("Final Kafka failure"))
                .when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        UUID outboxId = outbox.getId();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> outboxDispatcher.dispatchOutboxEvent(outboxId))
                .isInstanceOf(GenerationJobException.class);

        GenerationJobOutbox failedOutbox = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(failedOutbox.getStatus()).isEqualTo(GenerationJobOutboxStatus.FAILED);
        assertThat(failedOutbox.getAttemptCount()).isEqualTo(5);

        GenerationJob failedJob = generationJobRepository.findById(job.getId()).orElseThrow();
        assertThat(failedJob.getStatus()).isEqualTo(GenerationJobStatus.FAILED);
        assertThat(failedJob.getCompletedAt()).isNotNull();
        assertThat(failedJob.getErrorMessage()).contains("Final Kafka failure");
        assertThat(getJobMetricCount(GenerationJobStatus.FAILED)).isEqualTo(failedBefore + 1.0);
    }

    @Test
    @DisplayName("12. Outer transaction commits job and outbox; post-commit Kafka failure leaves retryable PENDING outbox")
    void testOuterTransactionCommitWithKafkaFailureRetainsRetryableOutbox() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        double queuedBefore = getJobMetricCount(GenerationJobStatus.QUEUED);

        doThrow(new GenerationJobException("Kafka unavailable after commit"))
                .when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        GenerationJobResponse response = txTemplate.execute(status -> generationJobService.submitJob(
                projectId,
                runtimeId,
                new GenerateDataRequest("/pets", 3, 105L),
                userId
        ));

        assertThat(response).isNotNull();
        assertThat(getJobMetricCount(GenerationJobStatus.QUEUED)).isEqualTo(queuedBefore + 1.0);

        GenerationJob persistedJob = generationJobRepository.findById(response.jobId()).orElseThrow();
        assertThat(persistedJob.getStatus()).isEqualTo(GenerationJobStatus.QUEUED);

        GenerationJobOutbox persistedOutbox = outboxRepository.findByJobId(response.jobId()).orElseThrow();
        assertThat(persistedOutbox.getStatus()).isEqualTo(GenerationJobOutboxStatus.PENDING);
        assertThat(persistedOutbox.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("13. Fencing: stale worker resuming after recovery and newer retry cannot overwrite newer collection")
    void testStaleWorkerCannotOverwriteNewerRetryCollection() {
        doNothing().when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        GenerationJobResponse job1Resp = generationJobService.submitJob(
                projectId, runtimeId, new GenerateDataRequest("/pets", 2, 10L), userId);
        GenerationJobResponse job2Resp = generationJobService.submitJob(
                projectId, runtimeId, new GenerateDataRequest("/pets", 3, 20L), userId);

        GenerationJob job1 = generationJobRepository.findById(job1Resp.jobId()).orElseThrow();
        GenerationJob job2 = generationJobRepository.findById(job2Resp.jobId()).orElseThrow();
        assertThat(job2.getFencingToken()).isGreaterThan(job1.getFencingToken());

        GenerationJobEvent event1 = new GenerationJobEvent(
                job1.getId(), runtimeId, projectId, "/pets", 2, 10L, job1.getFencingToken());
        GenerationJobEvent event2 = new GenerationJobEvent(
                job2.getId(), runtimeId, projectId, "/pets", 3, 20L, job2.getFencingToken());

        // Job 1 starts generation, gets marked FAILED by recovery, Job 2 completes and writes 3 entities,
        // then Job 1 resumes and attempts to write its 2 entities.
        doAnswer(invocation -> {
            long countArg = ((Number) invocation.getArgument(1)).longValue();
            if (countArg == 2L) {
                outboxDispatcher.recoverStaleRunningJobs(Duration.ZERO);
                generationJobService.processJob(event2);
            }
            return invocation.callRealMethod();
        }).when(mockDataGenerator).generateCollection(any(), any(Integer.class), any(Long.class), any());

        try {
            generationJobService.processJob(event1);
        } finally {
            org.mockito.Mockito.reset(mockDataGenerator);
        }

        GenerationJob finalJob1 = generationJobRepository.findById(job1.getId()).orElseThrow();
        GenerationJob finalJob2 = generationJobRepository.findById(job2.getId()).orElseThrow();
        assertThat(finalJob1.getStatus()).isEqualTo(GenerationJobStatus.FAILED);
        assertThat(finalJob2.getStatus()).isEqualTo(GenerationJobStatus.COMPLETED);
        // State store must still contain Job 2's 3 entities, not Job 1's 2 entities
        assertThat(stateStore.getCollection(runtimeId, "/pets")).hasSize(3);
    }

    @Test
    @DisplayName("14. Concurrent job submissions allocate strictly unique monotonic fencing tokens")
    void testConcurrentJobSubmissionsAllocateUniqueMonotonicTokens() throws Exception {
        doNothing().when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<UUID>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final long seed = 200L + i;
            futures.add(pool.submit(() -> {
                startGate.await();
                return generationJobService.submitJob(
                        projectId, runtimeId, new GenerateDataRequest("/pets", 2, seed), userId
                ).jobId();
            }));
        }
        startGate.countDown();
        List<Long> tokens = new ArrayList<>();
        for (Future<UUID> f : futures) {
            UUID jid = f.get();
            tokens.add(generationJobRepository.findById(jid).orElseThrow().getFencingToken());
        }
        pool.shutdown();

        assertThat(tokens).doesNotHaveDuplicates();
        assertThat(tokens).allSatisfy(t -> assertThat(t).isPositive());
    }

    @Test
    @DisplayName("15. Synchronous generateMockData uses monotonic fencing token and blocks older async job")
    void testSynchronousGenerateMockDataFencesOlderAsyncJob() {
        doNothing().when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        // Queue async job first (gets lower fencing token)
        GenerationJobResponse asyncResp = generationJobService.submitJob(
                projectId, runtimeId, new GenerateDataRequest("/pets", 2, 10L), userId);
        GenerationJob asyncJob = generationJobRepository.findById(asyncResp.jobId()).orElseThrow();
        GenerationJobEvent asyncEvent = new GenerationJobEvent(
                asyncJob.getId(), runtimeId, projectId, "/pets", 2, 10L, asyncJob.getFencingToken());

        // Synchronous generation runs next (gets higher fencing token)
        var syncResp = runtimeService.generateMockData(
                projectId, runtimeId, new GenerateDataRequest("/pets", 4, 99L), userId);
        assertThat(syncResp.generatedCount()).isEqualTo(4);
        assertThat(stateStore.getCollection(runtimeId, "/pets")).hasSize(4);

        // Older async job runs afterward and must be rejected by fencing
        generationJobService.processJob(asyncEvent);

        GenerationJob finalAsync = generationJobRepository.findById(asyncJob.getId()).orElseThrow();
        assertThat(finalAsync.getStatus()).isEqualTo(GenerationJobStatus.FAILED);
        assertThat(stateStore.getCollection(runtimeId, "/pets")).hasSize(4);
    }

    @Test
    @DisplayName("16. deleteRuntime while worker is paused prevents resurrection in state store")
    void testDeleteRuntimePreventsWorkerResurrection() {
        doNothing().when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        GenerationJobResponse jobResp = generationJobService.submitJob(
                projectId, runtimeId, new GenerateDataRequest("/pets", 3, 42L), userId);
        GenerationJob job = generationJobRepository.findById(jobResp.jobId()).orElseThrow();
        GenerationJobEvent event = new GenerationJobEvent(
                job.getId(), runtimeId, projectId, "/pets", 3, 42L, job.getFencingToken());

        doAnswer(invocation -> {
            runtimeService.deleteRuntime(projectId, runtimeId, userId);
            return invocation.callRealMethod();
        }).when(mockDataGenerator).generateCollection(any(), any(Integer.class), any(Long.class), any());

        try {
            generationJobService.processJob(event);
        } finally {
            org.mockito.Mockito.reset(mockDataGenerator);
        }

        assertThat(runtimeRepository.findById(runtimeId)).isEmpty();
        assertThat(stateStore.hasCollection(runtimeId, "/pets")).isFalse();
        assertThat(stateStore.getCollection(runtimeId, "/pets")).isEmpty();
    }

    private record IngestContractDto(String name, String content, String sourceType) {}
}
