package com.mockapilab.modules.runtime.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka configuration for topics and messaging infrastructure.
 */
@Configuration
public class KafkaConfig {

    @Value("${mockapilab.kafka.topics.generation-jobs:mockapi.generation.jobs}")
    private String generationJobsTopic;

    @Bean
    @ConditionalOnProperty(name = "mockapilab.kafka.create-topics", havingValue = "true", matchIfMissing = true)
    public NewTopic generationJobsTopic() {
        return TopicBuilder.name(generationJobsTopic)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
