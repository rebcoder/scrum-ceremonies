package com.scrumceremonies.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Privacy-safe usage logger that emits Micrometer metrics as structured log lines for any log aggregator.
 * 
 * Logs application usage metrics in a machine-parseable format:
 * APP_USAGE visitors_total=<number> active_users=<number> websocket_connections=<number>
 * 
 * NO personal data collected (no IPs, no user IDs, no cookies).
 * Runs every 60 seconds to log current metric values.
 */
@Component
public class AppUsageLogger {
    private static final Logger log = LoggerFactory.getLogger(AppUsageLogger.class);
    
    private final MeterRegistry meterRegistry;
    
    @Value("${app.usage.logging.enabled:true}")
    private boolean loggingEnabled;
    
    @Autowired
    public AppUsageLogger(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    /**
     * Scheduled task to log usage metrics every 60 seconds.
     * Reads metrics from Micrometer and logs them in a structured, grep-friendly format.
     * 
     * Handles missing metrics gracefully by defaulting to 0.
     * Does not throw exceptions to avoid breaking scheduled task execution.
     */
    @Scheduled(fixedRate = 60000) // Run every 60 seconds
    public void logUsageMetrics() {
        if (!loggingEnabled) {
            return;
        }
        
        try {
            // Read Counter: app.visitors.total
            double visitorsTotal = 0.0;
            var visitorsCounter = meterRegistry.find("app.visitors.total").counter();
            if (visitorsCounter != null) {
                visitorsTotal = visitorsCounter.count();
            }
            
            // Read Gauge: app.users.active
            double activeUsers = 0.0;
            var activeUsersGauge = meterRegistry.find("app.users.active").gauge();
            if (activeUsersGauge != null) {
                activeUsers = activeUsersGauge.value();
            }
            
            // Read Gauge: app.websocket.connections
            double websocketConnections = 0.0;
            var websocketGauge = meterRegistry.find("app.websocket.connections").gauge();
            if (websocketGauge != null) {
                websocketConnections = websocketGauge.value();
            }
            
            // Log in structured format: APP_USAGE visitors_total=<number> active_users=<number> websocket_connections=<number>
            log.info("APP_USAGE visitors_total={} active_users={} websocket_connections={}",
                    (long) visitorsTotal, (long) activeUsers, (long) websocketConnections);
                    
        } catch (Exception e) {
            // Fail silently to not break scheduled task execution
            // Metrics may be temporarily unavailable, but logging should continue
            log.warn("Failed to log usage metrics: {}", e.getMessage());
        }
    }
}

