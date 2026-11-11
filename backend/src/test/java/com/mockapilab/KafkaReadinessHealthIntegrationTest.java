package com.mockapilab;

import com.mockapilab.modules.runtime.messaging.GenerationJobConsumer;
import com.mockapilab.modules.runtime.messaging.GenerationJobProducer;
import com.mockapilab.modules.runtime.messaging.KafkaGenerationJobProducer;
import com.mockapilab.modules.runtime.observability.KafkaHealthIndicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "management.health.kafka.enabled=true",
        "management.endpoint.health.group.readiness.include=readinessState,db,runtimeStateStore,kafka",
        "spring.kafka.bootstrap-servers=127.0.0.1:19099",
        "mockapilab.kafka.health-timeout-ms=500",
        "mockapilab.kafka.producer.enabled=true",
        "mockapilab.kafka.consumer.auto-startup=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaReadinessHealthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private KafkaListenerEndpointRegistry listenerEndpointRegistry;

    @Test
    @DisplayName("1. Context starts cleanly with kafka readiness contributor and registers bean named 'kafka'")
    void testKafkaHealthContributorBeanRegistered() {
        Map<String, HealthIndicator> indicators = applicationContext.getBeansOfType(HealthIndicator.class);
        assertThat(indicators).containsKey("kafka");
        assertThat(indicators.get("kafka")).isInstanceOf(KafkaHealthIndicator.class);

        GenerationJobProducer producer = applicationContext.getBean(GenerationJobProducer.class);
        assertThat(producer).isInstanceOf(KafkaGenerationJobProducer.class);
        assertThat(applicationContext.getBean(GenerationJobConsumer.class)).isNotNull();
    }

    @Test
    @DisplayName("2. Readiness endpoint reports Kafka as DOWN when broker is unavailable")
    void testReadinessReportsKafkaDownWhenBrokerUnavailable() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status", is("DOWN")))
                .andExpect(jsonPath("$.components.db.status", is("UP")))
                .andExpect(jsonPath("$.components.runtimeStateStore.status", is("UP")))
                .andExpect(jsonPath("$.components.kafka.status", is("DOWN")))
                .andExpect(jsonPath("$.components.kafka.details.error", notNullValue()));
    }

    @Test
    @DisplayName("3. Default production application.yml keeps kafka in readiness and Kafka producer/consumer enabled")
    void testProductionDefaultsKeepKafkaAndReadinessEnabled() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        Properties mainProps = yaml.getObject();

        assertThat(mainProps).isNotNull();
        assertThat(mainProps.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db,runtimeStateStore,kafka");
        assertThat(mainProps.getProperty("mockapilab.kafka.create-topics"))
                .isEqualTo("${KAFKA_CREATE_TOPICS:true}");
        assertThat(mainProps.getProperty("mockapilab.kafka.consumer.auto-startup"))
                .isEqualTo("${KAFKA_CONSUMER_AUTO_STARTUP:true}");
        assertThat(mainProps.getProperty("mockapilab.kafka.producer.enabled")).isNull();
        assertThat(listenerEndpointRegistry.getListenerContainers())
                .extracting(MessageListenerContainer::getGroupId)
                .isNotEmpty();
    }
}
