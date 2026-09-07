package com.scrumceremonies.cache;

import com.scrumceremonies.integration.RedisTestConfiguration;
import com.scrumceremonies.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Redis-based rate limiting
 * 
 * Uses Testcontainers Redis for isolated test environment.
 * 
 * NOTE: Enabled in CI where Docker/Testcontainers is available.
 * Skipped unless SKIP_CACHE_TESTS is set to exactly "false". Unset - the normal local
 * default - also skips. To run locally (Docker required): SKIP_CACHE_TESTS=false mvn test
 */
@EnabledIfEnvironmentVariable(named = "SKIP_CACHE_TESTS", matches = "false", disabledReason = "Enabled ONLY when SKIP_CACHE_TESTS is exactly 'false' (CI sets it). Unset - the normal local default - also SKIPS. Requires Docker: RedisTestConfiguration starts a Testcontainers redis:7-alpine container, not host Redis. Run locally with: SKIP_CACHE_TESTS=false mvn test")
@SpringBootTest
@Import(RedisTestConfiguration.class)
@TestPropertySource(properties = {
    "app.rate-limit.room-creation.per-minute=5",
    "app.rate-limit.room-creation.per-hour=20",
    "app.rate-limit.room-creation.per-day=100"
})
class RateLimitServiceCacheTest {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        // Clear rate limit keys (all prefixes)
        try {
            if (redisTemplate != null) {
                // Clear all rate limit keys (rate:ip:*)
                var keys = redisTemplate.keys("rate:ip:*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
                // Also clear old format keys if any
                var oldKeys = redisTemplate.keys("ratelimit:*");
                if (oldKeys != null && !oldKeys.isEmpty()) {
                    redisTemplate.delete(oldKeys);
                }
            }
        } catch (Exception e) {
            // Ignore Redis connection errors during setup
            // Tests will still run and may fail if Redis is truly needed
        }
    }

    @Test
    void testRoomCreationRateLimit() {
        String ipAddress = "192.168.1.100";
        
        // First 5 requests should be allowed (per-minute limit)
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimitService.canCreateRoom(ipAddress), 
                "Request " + (i + 1) + " should be allowed");
        }
        
        // 6th request should be blocked
        assertFalse(rateLimitService.canCreateRoom(ipAddress), 
            "6th request should be rate limited");
    }

    @Test
    void testJoinRoomRateLimit() {
        String ipAddress = "192.168.1.200";
        
        // First 20 requests should be allowed
        for (int i = 0; i < 20; i++) {
            assertTrue(rateLimitService.canJoinRoom(ipAddress), 
                "Request " + (i + 1) + " should be allowed");
        }
        
        // 21st request should be blocked
        assertFalse(rateLimitService.canJoinRoom(ipAddress), 
            "21st request should be rate limited");
    }

    @Test
    void testRateLimitPerIP() {
        String ip1 = "192.168.1.1";
        String ip2 = "192.168.1.2";
        
        // Exhaust limit for IP1 (5 per minute)
        for (int i = 0; i < 5; i++) {
            rateLimitService.canCreateRoom(ip1);
        }
        assertFalse(rateLimitService.canCreateRoom(ip1), "IP1 should be rate limited");
        
        // IP2 should still be allowed
        assertTrue(rateLimitService.canJoinRoom(ip2), "IP2 should not be rate limited");
    }

    @Test
    void testRateLimitWindowExpiration() throws InterruptedException {
        String ipAddress = "192.168.1.300";
        
        // Exhaust limit (5 per minute)
        for (int i = 0; i < 5; i++) {
            rateLimitService.canCreateRoom(ipAddress);
        }
        assertFalse(rateLimitService.canCreateRoom(ipAddress), "Should be rate limited");
        
        // Wait for window to expire (61 seconds)
        Thread.sleep(61000);
        
        // Should be allowed again
        assertTrue(rateLimitService.canCreateRoom(ipAddress), 
            "Should be allowed after window expiration");
    }

    @Test
    void testConcurrentRateLimiting() throws InterruptedException {
        String ipAddress = "192.168.1.400";
        int numThreads = 10; // More than the limit (5 per minute)
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger blockedCount = new AtomicInteger(0);
        
        // Concurrent requests
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    if (rateLimitService.canCreateRoom(ipAddress)) {
                        allowedCount.incrementAndGet();
                    } else {
                        blockedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    fail("Rate limit check should not throw exception: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        // Exactly 5 should be allowed (per-minute limit), rest should be blocked
        assertEquals(5, allowedCount.get(), "Exactly 5 requests should be allowed (per-minute limit)");
        assertEquals(5, blockedCount.get(), "5 requests should be blocked");
    }

    @Test
    void testRemainingRoomCreations() {
        String ipAddress = "192.168.1.500";
        
        // Initially should have full quota (5 per minute)
        int remaining = rateLimitService.getRemainingRoomCreations(ipAddress);
        assertEquals(5, remaining, "Should have full quota initially (5 per minute)");
        
        // Make some requests
        rateLimitService.canCreateRoom(ipAddress);
        rateLimitService.canCreateRoom(ipAddress);
        rateLimitService.canCreateRoom(ipAddress);
        
        // Check remaining
        remaining = rateLimitService.getRemainingRoomCreations(ipAddress);
        assertEquals(2, remaining, "Should have 2 remaining after 3 requests (5 per minute limit)");
    }

    @Test
    void testRateLimitWithDifferentOperations() {
        String ipAddress = "192.168.1.600";
        
        // Room creation and join should have separate limits
        for (int i = 0; i < 5; i++) {
            rateLimitService.canCreateRoom(ipAddress);
        }
        assertFalse(rateLimitService.canCreateRoom(ipAddress), "Room creation should be limited");
        
        // But join should still work (different limit)
        assertTrue(rateLimitService.canJoinRoom(ipAddress), "Join should still be allowed");
    }

    @Test
    void testRateLimitKeyFormat() {
        String ipAddress = "192.168.1.700";
        
        // Make a request
        rateLimitService.canCreateRoom(ipAddress);
        
        // Verify key exists in Redis (check per-minute key)
        // Key format: rate:ip:{ipAddress}:min
        String expectedKey = "rate:ip:" + ipAddress + ":min";
        String value = redisTemplate.opsForValue().get(expectedKey);
        assertNotNull(value, "Rate limit key should exist in Redis");
        assertEquals("1", value, "Rate limit counter should be 1");
    }
}

