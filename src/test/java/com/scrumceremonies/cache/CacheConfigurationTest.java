package com.scrumceremonies.cache;

import com.scrumceremonies.integration.RedisTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests cache configuration and setup
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
    "app.room.max-users=10"
})
class CacheConfigurationTest {

    @Autowired(required = false)
    private CacheManager cacheManager;

    @Test
    void testCacheManagerBeanExists() {
        assertNotNull(cacheManager, "CacheManager should be configured");
    }

    @Test
    void testCacheNamesExist() {
        assertNotNull(cacheManager, "CacheManager should be configured");
        
        // Verify all expected cache names exist
        assertNotNull(cacheManager.getCache("rooms"), "rooms cache should exist");
        assertNotNull(cacheManager.getCache("retroBoards"), "retroBoards cache should exist");
        assertNotNull(cacheManager.getCache("roomStates"), "roomStates cache should exist");
    }

    @Test
    void testCacheOperations() {
        assertNotNull(cacheManager, "CacheManager should be configured");
        
        var roomsCache = cacheManager.getCache("rooms");
        assertNotNull(roomsCache, "rooms cache should exist");
        
        try {
            // Test basic cache operations
            roomsCache.put("test-key", "test-value");
            assertEquals("test-value", roomsCache.get("test-key", String.class));
            
            roomsCache.evict("test-key");
            var cachedValue = roomsCache.get("test-key", String.class);
            assertNull(cachedValue, "Cache should be evicted");
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            // Skip test if Redis is not available
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Redis not available, skipping cache operations test");
        }
    }
}

