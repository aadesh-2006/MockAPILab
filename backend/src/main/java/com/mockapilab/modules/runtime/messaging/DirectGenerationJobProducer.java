package com.mockapilab.modules.runtime.messaging;

import com.mockapilab.modules.runtime.dto.GenerationJobEvent;
import com.mockapilab.modules.runtime.service.GenerationJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Direct generation job producer for local / testing environments without an active Kafka cluster.
 * Dispatches the event directly to the generation service worker processing logic.
 */
@Component
@ConditionalOnProperty(name = "mockapilab.kafka.producer.enabled", havingValue = "false")
public class DirectGenerationJobProducer implements GenerationJobProducer {

    private static final Logger log = LoggerFactory.getLogger(DirectGenerationJobProducer.class);

    private final GenerationJobService generationJobService;
    private final List<GenerationJobEvent> publishedEvents = Collections.synchronizedList(new ArrayList<>());

    public DirectGenerationJobProducer(@Lazy GenerationJobService generationJobService) {
        this.generationJobService = generationJobService;
    }

    @Override
    public void sendJob(GenerationJobEvent event) {
        log.info("DirectGenerationJobProducer executing job event directly for jobId: {}", event.jobId());
        publishedEvents.add(event);
        generationJobService.processJob(event);
    }

    public List<GenerationJobEvent> getPublishedEvents() {
        return new ArrayList<>(publishedEvents);
    }

    public void clearPublishedEvents() {
        publishedEvents.clear();
    }
}