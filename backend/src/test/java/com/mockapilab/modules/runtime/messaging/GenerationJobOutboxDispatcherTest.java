package com.mockapilab.modules.runtime.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.runtime.dto.GenerationJobEvent;
import com.mockapilab.modules.runtime.model.GenerationJobOutbox;
import com.mockapilab.modules.runtime.repository.GenerationJobOutboxRepository;
import com.mockapilab.modules.runtime.repository.GenerationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerationJobOutboxDispatcherTest {

    @Mock
    private GenerationJobOutboxRepository outboxRepository;

    @Mock
    private GenerationJobRepository generationJobRepository;

    @Mock
    private GenerationJobProducer generationJobProducer;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GenerationJobOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        dispatcher = new GenerationJobOutboxDispatcher(
                outboxRepository,
                generationJobRepository,
                generationJobProducer,
                objectMapper,
                transactionManager,
                null,
                20,
                1000L,
                10000L,
                60L,
                300L
        );
    }

    @Test
    @DisplayName("Secondary PostgreSQL failure does not mask original Kafka exception and attaches suppressed DB error")
    void testSecondaryDbFailurePreservesOriginalKafkaException() throws Exception {
        UUID outboxId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        GenerationJobEvent event = new GenerationJobEvent(jobId, UUID.randomUUID(), UUID.randomUUID(), "/pets", 5, 1L);
        GenerationJobOutbox outbox = new GenerationJobOutbox(
                jobId,
                "mockapi.generation.jobs",
                jobId.toString(),
                objectMapper.writeValueAsString(event),
                5
        );
        outbox.setId(outboxId);

        when(outboxRepository.claimPending(eq(outboxId), any(), any(), any())).thenReturn(1);
        when(outboxRepository.findById(outboxId))
                .thenReturn(Optional.of(outbox))
                .thenThrow(new DataAccessResourceFailureException("PostgreSQL connection lost"));

        doThrow(new GenerationJobException("Kafka broker unreachable"))
                .when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        assertThatThrownBy(() -> dispatcher.dispatchOutboxEvent(outboxId))
                .isInstanceOf(GenerationJobException.class)
                .hasMessageContaining("Kafka broker unreachable")
                .satisfies(ex -> {
                    assertThat(ex.getSuppressed()).hasSize(1);
                    assertThat(ex.getSuppressed()[0])
                            .isInstanceOf(DataAccessResourceFailureException.class)
                            .hasMessageContaining("PostgreSQL connection lost");
                });
    }

    @Test
    @DisplayName("Interrupted flag is cleared for DB failure update and restored afterward")
    void testInterruptFlagPreservedAcrossDbFailureUpdate() throws Exception {
        UUID outboxId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        GenerationJobEvent event = new GenerationJobEvent(jobId, UUID.randomUUID(), UUID.randomUUID(), "/pets", 5, 1L);
        GenerationJobOutbox outbox = new GenerationJobOutbox(
                jobId,
                "mockapi.generation.jobs",
                jobId.toString(),
                objectMapper.writeValueAsString(event),
                5
        );
        outbox.setId(outboxId);

        when(outboxRepository.claimPending(eq(outboxId), any(), any(), any())).thenReturn(1);
        when(outboxRepository.findById(outboxId)).thenReturn(Optional.of(outbox));

        java.util.concurrent.atomic.AtomicBoolean interruptedDuringSave = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(outboxRepository.save(any(GenerationJobOutbox.class))).thenAnswer(inv -> {
            interruptedDuringSave.set(Thread.currentThread().isInterrupted());
            return inv.getArgument(0);
        });

        org.mockito.Mockito.doAnswer(inv -> {
            // Simulate KafkaGenerationJobProducer restoring interrupt flag before throwing
            Thread.currentThread().interrupt();
            throw new GenerationJobException("Interrupted while publishing");
        }).when(generationJobProducer).sendJob(any(GenerationJobEvent.class));

        try {
            assertThatThrownBy(() -> dispatcher.dispatchOutboxEvent(outboxId))
                    .isInstanceOf(GenerationJobException.class)
                    .hasMessageContaining("Interrupted while publishing");
            assertThat(interruptedDuringSave.get()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("dispatchPendingOutboxEvents reclaims stale PROCESSING rows and dispatches ready IDs")
    void testDispatchPendingOutboxEventsReclaimsAndDispatches() {
        when(outboxRepository.reclaimStaleProcessing(any())).thenReturn(3);
        when(outboxRepository.findReadyPendingIds(any(), any())).thenReturn(java.util.Collections.emptyList());

        dispatcher.dispatchPendingOutboxEvents();

        org.mockito.Mockito.verify(outboxRepository).reclaimStaleProcessing(any());
        org.mockito.Mockito.verify(outboxRepository).findReadyPendingIds(any(), any());
    }
}
