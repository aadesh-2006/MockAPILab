package com.mockapilab.modules.runtime.messaging;

import com.mockapilab.modules.runtime.dto.GenerationJobEvent;
import com.mockapilab.modules.runtime.service.GenerationJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka worker consumer processing generation job requests asynchronously.
 */
@Component
public class GenerationJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobConsumer.class);

    private final GenerationJobService generationJobService;

    public GenerationJobConsumer(GenerationJobService generationJobService) {
        this.generationJobService = generationJobService;
    }

    @KafkaListener(
            topics = "${mockapilab.kafka.topics.generation-jobs:mockapi.generation.jobs}",
            groupId = "${spring.kafka.consumer.group-id:mockapilab-generation-workers}",
            autoStartup = "${mockapilab.kafka.consumer.auto-startup:true}"
    )
    public void consumeGenerationJob(GenerationJobEvent event) {
        log.info("Received GenerationJobEvent from Kafka for jobId: {}, runtimeId: {}, collection: {}, count: {}",
                event.jobId(), event.runtimeId(), event.collection(), event.count());
        try {
            generationJobService.processJob(event);
        } catch (Exception ex) {
            log.error("Unhandled error in GenerationJobConsumer for jobId: {}", event.jobId(), ex);
        }
    }
}
