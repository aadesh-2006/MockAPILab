package com.mockapilab.modules.runtime.messaging;

import com.mockapilab.modules.runtime.dto.GenerationJobEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Kafka implementation of GenerationJobProducer publishing to mockapi.generation.jobs.
 */
@Component
@ConditionalOnProperty(name = "mockapilab.kafka.producer.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaGenerationJobProducer implements GenerationJobProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaGenerationJobProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public KafkaGenerationJobProducer(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${mockapilab.kafka.topics.generation-jobs:mockapi.generation.jobs}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void sendJob(GenerationJobEvent event) {
        String key = event.jobId().toString();
        log.info("Publishing GenerationJobEvent to Kafka topic '{}' for jobId: {}, runtimeId: {}, collection: {}, count: {}",
                topic, event.jobId(), event.runtimeId(), event.collection(), event.count());
        try {
            kafkaTemplate.send(topic, key, event).get(5, TimeUnit.SECONDS);
            log.info("Successfully dispatched GenerationJobEvent for jobId: {} to Kafka topic '{}'", event.jobId(), topic);
        } catch (Exception ex) {
            log.error("Failed to publish GenerationJobEvent for jobId: {} to topic '{}'", event.jobId(), topic, ex);
            throw new GenerationJobException("Failed to publish generation job to Kafka topic: " + topic, ex);
        }
    }
}