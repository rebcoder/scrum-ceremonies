package com.scrumceremonies.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Guards against deploying multiple instances with SimpleBroker enabled.
 * SimpleBroker does not propagate WebSocket messages across instances,
 * so real-time collaboration silently breaks with >1 instance.
 *
 * Checks the WEBSITES_SCALE_OUT_LIMIT (Azure Container Apps) or
 * APP_EXPECTED_INSTANCES env var. If >1 and SimpleBroker is enabled,
 * logs an ERROR at startup and reports unhealthy via /actuator/health.
 */
@Component
public class BrokerScalingGuard implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(BrokerScalingGuard.class);

    @Value("${app.use.simple-broker:true}")
    private boolean useSimpleBroker;

    @Value("${app.expected-instances:1}")
    private int expectedInstances;

    private boolean scalingMisconfigured = false;

    @EventListener(ApplicationReadyEvent.class)
    public void checkScalingConfiguration() {
        // Azure Container Apps sets CONTAINER_APP_REPLICA_NAME when running
        String replicaName = System.getenv("CONTAINER_APP_REPLICA_NAME");
        boolean isAzure = replicaName != null;

        if (useSimpleBroker && expectedInstances > 1) {
            scalingMisconfigured = true;
            String message = "SCALING MISCONFIGURATION: SimpleBroker is enabled (app.use.simple-broker=true) " +
                    "but app.expected-instances=" + expectedInstances +
                    ". WebSocket messages will NOT propagate across instances. " +
                    "Set APP_USE_SIMPLE_BROKER=false and configure RabbitMQ before scaling beyond 1 instance.";
            log.error(message);
            throw new IllegalStateException(message);
        }

        if (useSimpleBroker && isAzure) {
            log.warn("SimpleBroker is enabled in Azure Container Apps. " +
                    "Ensure max replicas is set to 1, or switch to RabbitMQ relay " +
                    "(APP_USE_SIMPLE_BROKER=false) before scaling.");
        }

        if (!useSimpleBroker) {
            log.info("RabbitMQ STOMP relay enabled — horizontal scaling is supported.");
        }
    }

    @Override
    public Health health() {
        if (scalingMisconfigured) {
            return Health.down()
                    .withDetail("reason", "SimpleBroker enabled with multiple expected instances")
                    .withDetail("action", "Set APP_USE_SIMPLE_BROKER=false and configure RabbitMQ")
                    .build();
        }
        return Health.up()
                .withDetail("broker", useSimpleBroker ? "SimpleBroker (single-instance)" : "RabbitMQ relay")
                .withDetail("expectedInstances", expectedInstances)
                .build();
    }
}
