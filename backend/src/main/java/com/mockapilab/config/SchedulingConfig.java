package com.mockapilab.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring scheduled tasks (e.g., transactional outbox dispatching and stale job recovery).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mockapilab.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
