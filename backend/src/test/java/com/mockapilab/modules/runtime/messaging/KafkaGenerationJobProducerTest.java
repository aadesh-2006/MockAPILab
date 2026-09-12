package com.mockapilab.modules.runtime.messaging;

import com.mockapilab.modules.runtime.dto.GenerationJobEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaGenerationJobProducerTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private KafkaGenerationJobProducer producer;
    private final String testTopic = "mockapi.generation.jobs.test";

    @BeforeEach
    void setUp() {
        producer = new KafkaGenerationJobProducer(kafkaTemplate, testTopic);
    }

    @Test
    @DisplayName("1. sendJob publishes event with jobId as record key")
    void testSendJobSuccess() {
        UUID jobId = UUID.randomUUID();
        UUID runtimeId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        GenerationJobEvent event = new GenerationJobEvent(jobId, runtimeId, projectId, "/pets", 10, 42L);

        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.complete(null);
        when(kafkaTemplate.send(eq(testTopic), eq(jobId.toString()), any(GenerationJobEvent.class)))
                .thenReturn(future);

        producer.sendJob(event);

        ArgumentCaptor<GenerationJobEvent> eventCaptor = ArgumentCaptor.forClass(GenerationJobEvent.class);
        verify(kafkaTemplate).send(eq(testTopic), eq(jobId.toString()), eventCaptor.capture());

        GenerationJobEvent captured = eventCaptor.getValue();
        assertThat(captured.jobId()).isEqualTo(jobId);
        assertThat(captured.runtimeId()).isEqualTo(runtimeId);
        assertThat(captured.collection()).isEqualTo("/pets");
        assertThat(captured.count()).isEqualTo(10);
        assertThat(captured.requestedSeed()).isEqualTo(42L);
    }

    @Test
    @DisplayName("2. sendJob wraps Kafka publishing failure into GenerationJobException")
    void testSendJobFailure() {
        UUID jobId = UUID.randomUUID();
        GenerationJobEvent event = new GenerationJobEvent(jobId, UUID.randomUUID(), UUID.randomUUID(), "/pets", 10, 42L);

        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Kafka broker unreachable"));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(future);

        assertThatThrownBy(() -> producer.sendJob(event))
                .isInstanceOf(GenerationJobException.class)
                .hasMessageContaining("Failed to publish generation job to Kafka topic");
    }
}