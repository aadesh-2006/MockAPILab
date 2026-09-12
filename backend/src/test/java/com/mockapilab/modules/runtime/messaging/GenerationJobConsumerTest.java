package com.mockapilab.modules.runtime.messaging;

import com.mockapilab.modules.runtime.dto.GenerationJobEvent;
import com.mockapilab.modules.runtime.service.GenerationJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GenerationJobConsumerTest {

    @Mock
    private GenerationJobService generationJobService;

    private GenerationJobConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new GenerationJobConsumer(generationJobService);
    }

    @Test
    @DisplayName("1. Consumer receives event and delegates to GenerationJobService.processJob")
    void testConsumeEvent() {
        GenerationJobEvent event = new GenerationJobEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "/orders",
                25,
                777L
        );

        consumer.consumeGenerationJob(event);

        verify(generationJobService).processJob(event);
    }
}