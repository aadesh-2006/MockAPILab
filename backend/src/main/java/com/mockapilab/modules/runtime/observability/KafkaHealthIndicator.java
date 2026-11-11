package com.mockapilab.modules.runtime.observability;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InterruptException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Spring Boot Actuator HealthIndicator registered as {@code kafka} that checks
 * live broker connectivity via Kafka's {@link AdminClient} metadata API.
 */
@Component("kafka")
@ConditionalOnEnabledHealthIndicator("kafka")
public class KafkaHealthIndicator implements HealthIndicator, DisposableBean, AutoCloseable {

    private static final long DEFAULT_TIMEOUT_MS = 2000L;
    private static final String DEFAULT_CLIENT_ID = "mockapilab-kafka-health";

    private final KafkaAdmin kafkaAdmin;
    private final long timeoutMs;
    private final Function<Map<String, Object>, AdminClient> adminClientFactory;
    private final Object clientLock = new Object();

    private AdminClient adminClient;
    private boolean closed;

    @Autowired
    public KafkaHealthIndicator(
            KafkaAdmin kafkaAdmin,
            @Value("${mockapilab.kafka.health-timeout-ms:2000}") long timeoutMs
    ) {
        this(kafkaAdmin, timeoutMs, AdminClient::create);
    }

    KafkaHealthIndicator(
            KafkaAdmin kafkaAdmin,
            long timeoutMs,
            Function<Map<String, Object>, AdminClient> adminClientFactory
    ) {
        this.kafkaAdmin = kafkaAdmin;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        this.adminClientFactory = adminClientFactory;
    }

    @Override
    public Health health() {
        try {
            AdminClient client = getOrCreateAdminClient();
            int boundedTimeoutMs = (int) Math.min(this.timeoutMs, Integer.MAX_VALUE);

            DescribeClusterResult clusterResult = client.describeCluster(
                    new DescribeClusterOptions().timeoutMs(boundedTimeoutMs)
            );

            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(this.timeoutMs);
            String clusterId = clusterResult.clusterId().get(this.timeoutMs, TimeUnit.MILLISECONDS);
            long remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
            Collection<Node> nodes = clusterResult.nodes().get(remainingMs, TimeUnit.MILLISECONDS);

            if (nodes == null || nodes.isEmpty()) {
                return Health.down()
                        .withDetail("error", "Kafka cluster metadata returned no active brokers")
                        .build();
            }

            Health.Builder builder = Health.up()
                    .withDetail("brokerCount", nodes.size())
                    .withDetail("status", "OPERATIONAL");

            if (clusterId != null && !clusterId.isBlank()) {
                builder.withDetail("clusterId", clusterId);
            }

            return builder.build();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Health.down()
                    .withDetail("error", "Kafka broker health check was interrupted")
                    .build();
        } catch (TimeoutException ex) {
            return Health.down()
                    .withDetail("error", "Kafka broker health check timed out after " + timeoutMs + "ms")
                    .build();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return Health.down()
                    .withDetail("error", toSafeDiagnosticMessage(cause))
                    .build();
        } catch (Exception ex) {
            return Health.down()
                    .withDetail("error", toSafeDiagnosticMessage(ex))
                    .build();
        }
    }

    private AdminClient getOrCreateAdminClient() {
        synchronized (clientLock) {
            if (closed) {
                throw new IllegalStateException("Kafka health indicator has been closed");
            }
            if (adminClient == null) {
                adminClient = adminClientFactory.apply(buildAdminConfig());
            }
            return adminClient;
        }
    }

    Map<String, Object> buildAdminConfig() {
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        config.putIfAbsent(AdminClientConfig.CLIENT_ID_CONFIG, DEFAULT_CLIENT_ID);

        int intTimeout = (int) Math.min(this.timeoutMs, Integer.MAX_VALUE);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
                (int) boundTimeout(config.get(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG), intTimeout));
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                (int) boundTimeout(config.get(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG), intTimeout));
        config.put(AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG,
                boundTimeout(config.get(AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG), this.timeoutMs));
        config.put(AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG,
                boundTimeout(config.get(AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG), this.timeoutMs));

        return config;
    }

    private long boundTimeout(Object existing, long maxTimeoutMs) {
        if (existing instanceof Number num && num.longValue() > 0) {
            return Math.min(num.longValue(), maxTimeoutMs);
        }
        if (existing instanceof String text) {
            try {
                long parsed = Long.parseLong(text.trim());
                if (parsed > 0) {
                    return Math.min(parsed, maxTimeoutMs);
                }
            } catch (NumberFormatException ignored) {
                return maxTimeoutMs;
            }
        }
        return maxTimeoutMs;
    }

    private String toSafeDiagnosticMessage(Throwable throwable) {
        if (throwable instanceof InterruptedException || throwable instanceof InterruptException) {
            Thread.currentThread().interrupt();
            return "Kafka broker health check was interrupted";
        }
        if (throwable instanceof TimeoutException
                || throwable instanceof org.apache.kafka.common.errors.TimeoutException
                || hasTimeoutMessage(throwable)) {
            return "Kafka broker health check timed out after " + timeoutMs + "ms";
        }
        if (throwable instanceof AuthenticationException || throwable instanceof AuthorizationException) {
            return "Kafka broker security check failed (" + throwable.getClass().getSimpleName() + ")";
        }
        if (throwable instanceof ConfigException) {
            return "Kafka client configuration invalid (" + throwable.getClass().getSimpleName() + ")";
        }

        return "Kafka cluster metadata unavailable (" + throwable.getClass().getSimpleName() + ")";
    }

    private boolean hasTimeoutMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("timed out") || normalized.contains("timeout");
    }

    @Override
    public void destroy() {
        close();
    }

    @Override
    public void close() {
        AdminClient clientToClose;
        synchronized (clientLock) {
            closed = true;
            clientToClose = this.adminClient;
            this.adminClient = null;
        }
        if (clientToClose != null) {
            clientToClose.close(Duration.ofMillis(timeoutMs));
        }
    }
}
