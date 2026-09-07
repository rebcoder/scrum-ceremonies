package com.scrumceremonies.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrokerScalingGuardTest {

    @Test
    @DisplayName("reports healthy when SimpleBroker with 1 instance")
    void healthyWithSingleInstance() {
        BrokerScalingGuard guard = new BrokerScalingGuard();
        ReflectionTestUtils.setField(guard, "useSimpleBroker", true);
        ReflectionTestUtils.setField(guard, "expectedInstances", 1);

        guard.checkScalingConfiguration();

        Health health = guard.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("broker")).isEqualTo("SimpleBroker (single-instance)");
    }

    @Test
    @DisplayName("throws IllegalStateException when SimpleBroker with multiple instances")
    void throwsWhenSimpleBrokerWithMultipleInstances() {
        BrokerScalingGuard guard = new BrokerScalingGuard();
        ReflectionTestUtils.setField(guard, "useSimpleBroker", true);
        ReflectionTestUtils.setField(guard, "expectedInstances", 3);

        assertThatThrownBy(guard::checkScalingConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SCALING MISCONFIGURATION")
                .hasMessageContaining("SimpleBroker");
    }

    @Test
    @DisplayName("reports healthy when RabbitMQ relay with multiple instances")
    void healthyWithRabbitMQRelay() {
        BrokerScalingGuard guard = new BrokerScalingGuard();
        ReflectionTestUtils.setField(guard, "useSimpleBroker", false);
        ReflectionTestUtils.setField(guard, "expectedInstances", 3);

        guard.checkScalingConfiguration();

        Health health = guard.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("broker")).isEqualTo("RabbitMQ relay");
    }
}
