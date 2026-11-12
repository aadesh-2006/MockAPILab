package com.mockapilab.modules.runtime.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.runtime.dto.GenerationJobEvent;
import com.mockapilab.modules.runtime.model.GenerationJob;
import com.mockapilab.modules.runtime.model.GenerationJobOutbox;
import com.mockapilab.modules.runtime.model.GenerationJobOutboxStatus;
import com.mockapilab.modules.runtime.model.GenerationJobStatus;
import com.mockapilab.modules.runtime.observability.MockApiLabMetrics;
import com.mockapilab.modules.runtime.repository.GenerationJobOutboxRepository;
import com.mockapilab.modules.runtime.repository.GenerationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reliable multi-instance dispatcher for {@link GenerationJobOutbox} events.
 * Claims pending outbox events atomically, publishes to Kafka via {@link GenerationJobProducer},
 * retries transient failures with bounded exponential backoff, and recovers stale RUNNING jobs.
 */
@Component
public class GenerationJobOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobOutboxDispatcher.class);
    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;
    private static final Pattern SENSITIVE_KV_PATTERN = Pattern.compile(
            "(?i)(password|secret|token|sasl\\.jaas\\.config|api[-_]?key|credential)\\s*[=:]\\s*[^\\s,;\"']+"
    );

    private final GenerationJobOutboxRepository outboxRepository;
    private final GenerationJobRepository generationJobRepository;
    private final GenerationJobProducer generationJobProducer;
    private final ObjectMapper objectMapper;
    private final MockApiLabMetrics metrics;
    private final TransactionTemplate requiresNewTxTemplate;
    private final String instanceId;
    private final int batchSize;
    private final long baseBackoffMs;
    private final long maxBackoffMs;
    private final long leaseTimeoutSeconds;
    private final long staleRunningSeconds;

    public GenerationJobOutboxDispatcher(
            GenerationJobOutboxRepository outboxRepository,
            GenerationJobRepository generationJobRepository,
            GenerationJobProducer generationJobProducer,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Autowired(required = false) MockApiLabMetrics metrics,
            @Value("${mockapilab.kafka.outbox.batch-size:20}") int batchSize,
            @Value("${mockapilab.kafka.outbox.base-backoff-ms:2000}") long baseBackoffMs,
            @Value("${mockapilab.kafka.outbox.max-backoff-ms:60000}") long maxBackoffMs,
            @Value("${mockapilab.kafka.outbox.lease-timeout-seconds:60}") long leaseTimeoutSeconds,
            @Value("${mockapilab.kafka.outbox.stale-running-seconds:300}") long staleRunningSeconds
    ) {
        this.outboxRepository = outboxRepository;
        this.generationJobRepository = generationJobRepository;
        this.generationJobProducer = generationJobProducer;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.requiresNewTxTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.instanceId = "dispatcher-" + UUID.randomUUID();
        this.batchSize = Math.max(1, batchSize);
        this.baseBackoffMs = Math.max(100L, baseBackoffMs);
        this.maxBackoffMs = Math.max(this.baseBackoffMs, maxBackoffMs);
        this.leaseTimeoutSeconds = Math.max(5L, leaseTimeoutSeconds);
        this.staleRunningSeconds = Math.max(10L, staleRunningSeconds);
    }

    /**
     * Periodically reclaims stale PROCESSING outbox rows and dispatches ready PENDING events.
     */
    @Scheduled(fixedDelayString = "${mockapilab.kafka.outbox.poll-interval-ms:5000}")
    public void dispatchPendingOutboxEvents() {
        Instant now = Instant.now();
        Instant staleBefore = now.minusSeconds(leaseTimeoutSeconds);

        Integer reclaimed = requiresNewTxTemplate.execute(status ->
                outboxRepository.reclaimStaleProcessing(staleBefore)
        );
        if (reclaimed != null && reclaimed > 0) {
            log.warn("Reclaimed {} stale PROCESSING outbox entry/entries older than {}", reclaimed, staleBefore);
        }

        List<UUID> readyIds = requiresNewTxTemplate.execute(status ->
                outboxRepository.findReadyPendingIds(now, PageRequest.of(0, batchSize))
        );
        if (readyIds == null || readyIds.isEmpty()) {
            return;
        }

        for (UUID outboxId : readyIds) {
            try {
                dispatchOutboxEvent(outboxId);
            } catch (Exception ex) {
                log.warn("Scheduled outbox dispatch failed for outboxId={}: {}", outboxId, sanitizeErrorMessage(ex.getMessage()));
            }
        }
    }

    /**
     * Periodically transitions jobs stuck in RUNNING past the stale threshold to FAILED.
     */
    @Scheduled(fixedDelayString = "${mockapilab.kafka.outbox.stale-job-recovery-interval-ms:60000}")
    public int recoverStaleRunningJobs() {
        return recoverStaleRunningJobs(Duration.ofSeconds(staleRunningSeconds));
    }

    /**
     * Transitions jobs stuck in RUNNING longer than {@code staleThreshold} to FAILED.
     */
    public int recoverStaleRunningJobs(Duration staleThreshold) {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(staleThreshold);
        Integer recovered = requiresNewTxTemplate.execute(status ->
                generationJobRepository.failStaleRunningJobs(
                        staleBefore,
                        now,
                        "Generation worker timed out or terminated unexpectedly before completion"
                )
        );
        int count = recovered != null ? recovered : 0;
        if (count > 0) {
            log.warn("Recovered {} stale RUNNING generation job(s) started before {}", count, staleBefore);
            if (metrics != null) {
                for (int i = 0; i < count; i++) {
                    metrics.recordGenerationJob(GenerationJobStatus.FAILED);
                }
            }
        }
        return count;
    }

    /**
     * Atomically claims and dispatches a single outbox event by ID.
     *
     * @return true if claimed and published, false if already claimed/non-pending/invalid payload
     */
    public boolean dispatchOutboxEvent(UUID outboxId) {
        Instant now = Instant.now();
        ClaimedOutbox claimed = requiresNewTxTemplate.execute(status -> {
            int updated = outboxRepository.claimPending(outboxId, instanceId, now, now);
            if (updated != 1) {
                return null;
            }
            GenerationJobOutbox outbox = outboxRepository.findById(outboxId).orElse(null);
            if (outbox == null) {
                return null;
            }
            return new ClaimedOutbox(outbox.getId(), outbox.getJobId(), outbox.getPayload());
        });

        if (claimed == null) {
            return false;
        }

        GenerationJobEvent event;
        try {
            event = objectMapper.readValue(claimed.payload(), GenerationJobEvent.class);
        } catch (Exception ex) {
            String sanitized = sanitizeErrorMessage("Corrupt outbox event payload: " + ex.getMessage());
            markPermanentOutboxAndJobFailure(claimed.outboxId(), claimed.jobId(), sanitized);
            return false;
        }

        try {
            generationJobProducer.sendJob(event);
        } catch (Exception ex) {
            handleKafkaPublishFailure(claimed.outboxId(), ex);
            return false;
        }

        requiresNewTxTemplate.executeWithoutResult(status -> {
            GenerationJobOutbox outbox = outboxRepository.findById(claimed.outboxId()).orElse(null);
            if (outbox != null) {
                outbox.setStatus(GenerationJobOutboxStatus.PUBLISHED);
                outbox.setPublishedAt(Instant.now());
                outbox.setLockedBy(null);
                outbox.setLockedAt(null);
                outbox.setLastError(null);
                outboxRepository.save(outbox);
            }
        });

        return true;
    }

    private void handleKafkaPublishFailure(UUID outboxId, Exception kafkaEx) {
        GenerationJobException preservedKafkaEx = kafkaEx instanceof GenerationJobException gje
                ? gje
                : new GenerationJobException("Failed to publish generation job to Kafka", kafkaEx);

        String sanitizedError = sanitizeErrorMessage("Failed to dispatch job to Kafka: " + kafkaEx.getMessage());
        log.error("Kafka dispatch failed for outboxId={}: {}", outboxId, sanitizedError);

        // Preserve and temporarily clear interrupt flag so the DB failure update can execute cleanly
        boolean wasInterrupted = Thread.interrupted();
        boolean jobMarkedFailed = false;
        try {
            Boolean failedResult = requiresNewTxTemplate.execute(status -> {
                GenerationJobOutbox outbox = outboxRepository.findById(outboxId).orElse(null);
                if (outbox == null) {
                    return false;
                }

                int nextAttempt = outbox.getAttemptCount() + 1;
                outbox.setAttemptCount(nextAttempt);
                outbox.setLockedBy(null);
                outbox.setLockedAt(null);
                outbox.setLastError(sanitizedError);

                if (nextAttempt >= outbox.getMaxAttempts()) {
                    outbox.setStatus(GenerationJobOutboxStatus.FAILED);
                    outboxRepository.save(outbox);
                    markQueuedJobFailed(outbox.getJobId(), sanitizedError);
                    return true;
                } else {
                    outbox.setStatus(GenerationJobOutboxStatus.PENDING);
                    outbox.setNextAttemptAt(Instant.now().plusMillis(computeBackoffMs(nextAttempt)));
                    outboxRepository.save(outbox);
                    return false;
                }
            });
            jobMarkedFailed = Boolean.TRUE.equals(failedResult);
        } catch (Exception dbEx) {
            log.error("Secondary database failure while recording outbox dispatch failure for outboxId={}: {}",
                    outboxId, dbEx.getMessage());
            preservedKafkaEx.addSuppressed(dbEx);
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }

        if (jobMarkedFailed && metrics != null) {
            metrics.recordGenerationJob(GenerationJobStatus.FAILED);
        }

        throw preservedKafkaEx;
    }

    private void markPermanentOutboxAndJobFailure(UUID outboxId, UUID jobId, String sanitizedError) {
        Boolean jobMarkedFailed = requiresNewTxTemplate.execute(status -> {
            GenerationJobOutbox outbox = outboxRepository.findById(outboxId).orElse(null);
            if (outbox != null) {
                outbox.setStatus(GenerationJobOutboxStatus.FAILED);
                outbox.setAttemptCount(outbox.getAttemptCount() + 1);
                outbox.setLockedBy(null);
                outbox.setLockedAt(null);
                outbox.setLastError(sanitizedError);
                outboxRepository.save(outbox);
            }
            return markQueuedJobFailed(jobId, sanitizedError);
        });
        if (Boolean.TRUE.equals(jobMarkedFailed) && metrics != null) {
            metrics.recordGenerationJob(GenerationJobStatus.FAILED);
        }
    }

    private boolean markQueuedJobFailed(UUID jobId, String sanitizedError) {
        GenerationJob job = generationJobRepository.findById(jobId).orElse(null);
        if (job != null && job.getStatus() == GenerationJobStatus.QUEUED) {
            job.setStatus(GenerationJobStatus.FAILED);
            job.setErrorMessage(sanitizedError);
            job.setCompletedAt(Instant.now());
            generationJobRepository.save(job);
            return true;
        }
        return false;
    }

    private long computeBackoffMs(int attemptCount) {
        int shift = Math.min(Math.max(0, attemptCount - 1), 10);
        long delay = baseBackoffMs * (1L << shift);
        return Math.min(delay, maxBackoffMs);
    }

    public static String sanitizeErrorMessage(String rawMessage) {
        if (rawMessage == null) {
            return null;
        }
        String redacted = SENSITIVE_KV_PATTERN.matcher(rawMessage).replaceAll("$1=[REDACTED]");
        if (redacted.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return redacted;
        }
        return redacted.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    private record ClaimedOutbox(UUID outboxId, UUID jobId, String payload) {
    }
}
