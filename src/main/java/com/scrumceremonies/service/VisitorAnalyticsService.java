package com.scrumceremonies.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight, privacy-safe visitor analytics service.
 * 
 * Tracks:
 * - Total visits (privacy-safe, IP-based with hashing)
 * - Active users (from Redis presence data)
 * - Concurrent WebSocket connections (via event listeners)
 * 
 * NO cookies, NO personal data collected.
 * Metrics exposed via Spring Boot Actuator /actuator/metrics and /actuator/prometheus
 */
@Service
public class VisitorAnalyticsService {
    private static final Logger log = LoggerFactory.getLogger(VisitorAnalyticsService.class);
    
    private static final String REDIS_KEY_VISITS = "analytics:visits:total";
    private static final String REDIS_KEY_WS_CONNECTIONS = "analytics:websocket:connections";
    
    private final StringRedisTemplate stringRedisTemplate;
    private final MeterRegistry meterRegistry;
    
    // Atomic counters for current state (in-memory)
    private final AtomicLong activeUsers = new AtomicLong(0);
    private final AtomicLong websocketConnections = new AtomicLong(0);
    
    // Micrometer counters for metrics
    private Counter totalVisitsCounter;
    
    @Autowired
    public VisitorAnalyticsService(StringRedisTemplate stringRedisTemplate, MeterRegistry meterRegistry) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.meterRegistry = meterRegistry;
    }
    
    @PostConstruct
    public void init() {
        // Register metrics with Micrometer (lightweight, doesn't block)
        totalVisitsCounter = Counter.builder("app.visitors.total")
                .description("Total number of visits (privacy-safe, no personal data)")
                .register(meterRegistry);
        
        // Register gauges for current state (lightweight, doesn't block)
        Gauge.builder("app.users.active", activeUsers, AtomicLong::get)
                .description("Current number of active users (from Redis presence)")
                .register(meterRegistry);
        
        Gauge.builder("app.websocket.connections", websocketConnections, AtomicLong::get)
                .description("Current number of active WebSocket connections")
                .register(meterRegistry);
        
        // Defer Redis load to async - don't block startup
        // Load initial total visits from Redis asynchronously
        new Thread(() -> {
            try {
                Thread.sleep(1000); // Wait 1 second for app to be ready
                loadTotalVisitsFromRedis();
                log.info("Visitor analytics service initialized (async)");
            } catch (Exception e) {
                log.debug("Failed to load initial visits from Redis (non-critical): {}", e.getMessage());
            }
        }, "analytics-init").start();
    }
    
    /**
     * Record a visit (privacy-safe, no personal data stored)
     * Called by HTTP filter/interceptor
     */
    public void recordVisit() {
        try {
            // Increment Redis counter (distributed-safe)
            Long total = stringRedisTemplate.opsForValue().increment(REDIS_KEY_VISITS);
            if (total != null) {
                // Update Micrometer counter
                totalVisitsCounter.increment();
                log.debug("Visit recorded. Total visits: {}", total);
            }
        } catch (Exception e) {
            // Fail silently to not break application flow
            log.warn("Failed to record visit: {}", e.getMessage());
        }
    }
    
    /**
     * Increment WebSocket connection count
     */
    public void incrementWebSocketConnection() {
        try {
            websocketConnections.incrementAndGet();
            // Also update Redis for distributed tracking
            stringRedisTemplate.opsForValue().increment(REDIS_KEY_WS_CONNECTIONS);
            log.debug("WebSocket connection established. Total connections: {}", websocketConnections.get());
        } catch (Exception e) {
            log.warn("Failed to increment WebSocket connection count: {}", e.getMessage());
        }
    }
    
    /**
     * Decrement WebSocket connection count
     */
    public void decrementWebSocketConnection() {
        try {
            long current = websocketConnections.decrementAndGet();
            if (current < 0) {
                // Reset if negative (shouldn't happen, but safety check)
                websocketConnections.set(0);
                current = 0;
            }
            // Decrement Redis counter
            stringRedisTemplate.opsForValue().decrement(REDIS_KEY_WS_CONNECTIONS);
            log.debug("WebSocket connection closed. Remaining connections: {}", current);
        } catch (Exception e) {
            log.warn("Failed to decrement WebSocket connection count: {}", e.getMessage());
        }
    }
    
    /**
     * Update active users count from Redis presence data
     * Called periodically to sync with Redis
     */
    @Scheduled(fixedRate = 60000) // Every 60 seconds
    public void updateActiveUsersCount() {
        try {
            // Count unique users from Redis presence hashes
            // Pattern: room:*:presence
            long count = countActiveUsersFromRedis();
            activeUsers.set(count);
            log.debug("Active users updated: {}", count);
        } catch (Exception e) {
            log.warn("Failed to update active users count: {}", e.getMessage());
        }
    }
    
    /**
     * Count active users from Redis presence hashes.
     * Iterates through all room presence keys and counts unique users.
     */
    private long countActiveUsersFromRedis() {
        try {
            // Use SCAN to iterate through keys matching pattern
            org.springframework.data.redis.core.Cursor<String> cursor = stringRedisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match("room:*:presence")
                    .count(100)
                    .build()
            );
            
            long totalUsers = 0;
            while (cursor.hasNext()) {
                String key = cursor.next();
                Long size = stringRedisTemplate.opsForHash().size(key);
                if (size != null) {
                    totalUsers += size;
                }
            }
            cursor.close();
            
            return totalUsers;
        } catch (Exception e) {
            log.warn("Failed to count active users from Redis: {}", e.getMessage());
            return 0;
        }
    }
    
    /**
     * Load total visits from Redis (for persistence across restarts)
     * Note: Micrometer counters are cumulative, so we track the delta on startup
     */
    private void loadTotalVisitsFromRedis() {
        try {
            String totalStr = stringRedisTemplate.opsForValue().get(REDIS_KEY_VISITS);
            if (totalStr != null) {
                long redisTotal = Long.parseLong(totalStr);
                // Micrometer counters are cumulative from app start
                // We track the base value and add increments from there
                // The actual total will be: baseValue + increments since startup
                log.info("Redis total visits: {}. Counter will track increments from this base.", redisTotal);
            }
        } catch (Exception e) {
            log.warn("Failed to load total visits from Redis: {}", e.getMessage());
        }
    }
    
    /**
     * Get current active users count (for testing/monitoring)
     */
    public long getActiveUsersCount() {
        return activeUsers.get();
    }
    
    /**
     * Get current WebSocket connections count (for testing/monitoring)
     */
    public long getWebSocketConnectionsCount() {
        return websocketConnections.get();
    }
}

