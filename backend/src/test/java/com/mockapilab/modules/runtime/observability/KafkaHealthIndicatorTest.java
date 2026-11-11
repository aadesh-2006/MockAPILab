package com.mockapilab.modules.runtime.observability;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class KafkaHealthIndicatorTest {

    @Test
    @DisplayName("1. Reports UP with safe cluster metadata when AdminClient returns reachable brokers")
    void testHealthUpWhenBrokerMetadataAvailable() throws Exception {
        KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "broker.internal:9092",
                CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL",
                SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-256",
                SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"user\" password=\"super-secret\";"
        ));

        AdminClient adminClient = mock(AdminClient.class);
        DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
        Node node = new Node(1, "broker.internal", 9092);

        when(clusterResult.clusterId()).thenReturn(KafkaFuture.completedFuture("cluster-abc-123"));
        when(clusterResult.nodes()).thenReturn(KafkaFuture.completedFuture(List.of(node)));
        when(adminClient.describeCluster(any(DescribeClusterOptions.class))).thenReturn(clusterResult);

        AtomicReference<Map<String, Object>> capturedConfig = new AtomicReference<>();
        KafkaHealthIndicator indicator = new KafkaHealthIndicator(kafkaAdmin, 1500L, config -> {
            capturedConfig.set(config);
            return adminClient;
        });

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("clusterId", "cluster-abc-123")
                .containsEntry("brokerCount", 1)
                .containsEntry("status", "OPERATIONAL");

        // Ensure no credentials, SASL details, or connection hosts leak into health response
        String serializedDetails = health.getDetails().toString();
        assertThat(serializedDetails)
                .doesNotContain("super-secret")
                .doesNotContain("ScramLoginModule")
                .doesNotContain("SASL_SSL")
                .doesNotContain("broker.internal");

        // Verify KafkaAdmin bootstrap & security config are reused with bounded timeouts
        assertThat(capturedConfig.get())
                .containsEntry(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "broker.internal:9092")
                .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL")
                .containsEntry(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-256")
                .containsEntry(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1500)
                .containsEntry(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 1500);

        ArgumentCaptor<DescribeClusterOptions> optionsCaptor = ArgumentCaptor.forClass(DescribeClusterOptions.class);
        verify(adminClient).describeCluster(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().timeoutMs()).isEqualTo(1500);

        indicator.close();
        verify(adminClient).close(Duration.ofMillis(1500L));
    }

    @Test
    @DisplayName("2. Reports DOWN when cluster metadata returns no active broker nodes")
    void testHealthDownWhenNoNodesAvailable() {
        KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"
        ));
        AdminClient adminClient = mock(AdminClient.class);
        DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);

        when(clusterResult.clusterId()).thenReturn(KafkaFuture.completedFuture("cluster-empty"));
        when(clusterResult.nodes()).thenReturn(KafkaFuture.completedFuture(Collections.emptyList()));
        when(adminClient.describeCluster(any(DescribeClusterOptions.class))).thenReturn(clusterResult);

        KafkaHealthIndicator indicator = new KafkaHealthIndicator(kafkaAdmin, 1000L, config -> adminClient);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(String.valueOf(health.getDetails().get("error"))).contains("no active brokers");
    }

    @Test
    @DisplayName("3. Reports DOWN with bounded timeout message when broker metadata request times out")
    @SuppressWarnings("unchecked")
    void testHealthDownOnBrokerTimeout() throws Exception {
        KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"
        ));
        AdminClient adminClient = mock(AdminClient.class);
        DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
        KafkaFuture<String> timedOutFuture = mock(KafkaFuture.class);
        KafkaFuture<Collection<Node>> nodesFuture = mock(KafkaFuture.class);

        when(timedOutFuture.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new TimeoutException("Timed out waiting for cluster metadata"));
        when(clusterResult.clusterId()).thenReturn(timedOutFuture);
        when(clusterResult.nodes()).thenReturn(nodesFuture);
        when(adminClient.describeCluster(any(DescribeClusterOptions.class))).thenReturn(clusterResult);

        KafkaHealthIndicator indicator = new KafkaHealthIndicator(kafkaAdmin, 750L, config -> adminClient);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(String.valueOf(health.getDetails().get("error"))).contains("timed out after 750ms");
    }

    @Test
    @DisplayName("4. Reports DOWN with sanitized error when AdminClient fails or throws SASL/credential error")
    void testHealthDownOnAdminClientFailureAndSanitizesSecrets() {
        KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"
        ));

        // Case A: AdminClient creation fails with sensitive JAAS message
        KafkaHealthIndicator creationFailureIndicator = new KafkaHealthIndicator(kafkaAdmin, 1000L, config -> {
            throw new KafkaException("Failed creating client with sasl.jaas.config=secretPassword123");
        });

        Health creationFailureHealth = creationFailureIndicator.health();
        assertThat(creationFailureHealth.getStatus()).isEqualTo(Status.DOWN);
        assertThat(String.valueOf(creationFailureHealth.getDetails().get("error")))
                .doesNotContain("secretPassword123")
                .doesNotContain("jaas")
                .contains("KafkaException");

        // Case B: Future fails with SASL authentication exception
        AdminClient adminClient = mock(AdminClient.class);
        DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
        KafkaFutureImpl<String> failedFuture = new KafkaFutureImpl<>();
        failedFuture.completeExceptionally(new SaslAuthenticationException("SCRAM secretPassword123 mismatch"));

        when(clusterResult.clusterId()).thenReturn(failedFuture);
        when(clusterResult.nodes()).thenReturn(KafkaFuture.completedFuture(List.of()));
        when(adminClient.describeCluster(any(DescribeClusterOptions.class))).thenReturn(clusterResult);

        KafkaHealthIndicator authFailureIndicator = new KafkaHealthIndicator(kafkaAdmin, 1000L, config -> adminClient);
        Health authHealth = authFailureIndicator.health();

        assertThat(authHealth.getStatus()).isEqualTo(Status.DOWN);
        assertThat(String.valueOf(authHealth.getDetails().get("error")))
                .contains("security check failed")
                .doesNotContain("secretPassword123");

        // Case C: ConfigException / runtime message containing bootstrap servers or username never leaks
        KafkaHealthIndicator configFailureIndicator = new KafkaHealthIndicator(kafkaAdmin, 1000L, config -> {
            throw new ConfigException("Couldn't resolve server secret-broker.upstash.io:9092 for username=prod-user");
        });
        Health configHealth = configFailureIndicator.health();

        assertThat(configHealth.getStatus()).isEqualTo(Status.DOWN);
        assertThat(String.valueOf(configHealth.getDetails().get("error")))
                .contains("ConfigException")
                .doesNotContain("secret-broker.upstash.io")
                .doesNotContain("9092")
                .doesNotContain("prod-user")
                .doesNotContain("username");
    }

    @Test
    @DisplayName("6. Preserves thread interrupt flag on InterruptedException and Kafka InterruptException")
    @SuppressWarnings("unchecked")
    void testInterruptFlagPreserved() throws Exception {
        KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"
        ));
        AdminClient adminClient = mock(AdminClient.class);
        DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
        KafkaFuture<String> interruptedFuture = mock(KafkaFuture.class);
        when(interruptedFuture.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("Thread interrupted"));
        when(clusterResult.clusterId()).thenReturn(interruptedFuture);
        when(adminClient.describeCluster(any(DescribeClusterOptions.class))).thenReturn(clusterResult);

        KafkaHealthIndicator indicator = new KafkaHealthIndicator(kafkaAdmin, 1000L, config -> adminClient);
        try {
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        KafkaHealthIndicator kafkaInterruptIndicator = new KafkaHealthIndicator(kafkaAdmin, 1000L, config -> {
            throw new InterruptException("Kafka admin interrupted");
        });
        try {
            Health health = kafkaInterruptIndicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("5. Reuses AdminClient across health checks and closes it cleanly on destroy()")
    void testAdminClientLifecycleAndDestroyCleanup() {
        KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"
        ));
        AdminClient adminClient = mock(AdminClient.class);
        DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
        when(clusterResult.clusterId()).thenReturn(KafkaFuture.completedFuture("cluster-1"));
        when(clusterResult.nodes()).thenReturn(KafkaFuture.completedFuture(List.of(new Node(0, "localhost", 9092))));
        when(adminClient.describeCluster(any(DescribeClusterOptions.class))).thenReturn(clusterResult);

        AtomicInteger creations = new AtomicInteger(0);
        KafkaHealthIndicator indicator = new KafkaHealthIndicator(kafkaAdmin, 1200L, config -> {
            creations.incrementAndGet();
            return adminClient;
        });

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(creations.get()).isEqualTo(1);

        indicator.destroy();
        verify(adminClient, times(2)).describeCluster(any(DescribeClusterOptions.class));
        verify(adminClient, times(1)).close(Duration.ofMillis(1200L));

        // Subsequent destroy() is idempotent and does not leak/re-close; health() after close returns DOWN
        indicator.destroy();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        verifyNoMoreInteractions(adminClient);
    }
}
