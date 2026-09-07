package com.scrumceremonies.cache;

import com.scrumceremonies.integration.RedisTestConfiguration;
import com.scrumceremonies.model.RetrospectiveBoard;
import com.scrumceremonies.service.RetrospectiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for RetrospectiveService caching
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
    "app.room.max-users=10",
    "app.room.user-ttl-seconds=60"
})
class RetrospectiveServiceCacheTest {

    @Autowired
    private RetrospectiveService retrospectiveService;

    @Autowired
    private CacheManager cacheManager;

    private String testBoardId;

    @BeforeEach
    void setUp() {
        clearCache();
        testBoardId = retrospectiveService.createBoard();
        assertNotNull(testBoardId, "Board should be created");
    }

    private void clearCache() {
        try {
            if (cacheManager != null) {
                var cache = cacheManager.getCache("retroBoards");
                if (cache != null) cache.clear();
            }
        } catch (Exception e) {
            // Ignore Redis connection errors during cache clearing
            // Tests will still run and may fail if Redis is truly needed
        }
    }

    @Test
    void testCacheHitOnSecondCall() {
        // First call - cache miss
        RetrospectiveBoard board1 = retrospectiveService.getBoard(testBoardId);
        assertNotNull(board1);
        
        // Verify cache
        var cache = cacheManager.getCache("retroBoards");
        var cached = cache != null ? cache.get(testBoardId) : null;
        assertNotNull(cached, "Board should be cached");
        
        // Second call - cache hit
        RetrospectiveBoard board2 = retrospectiveService.getBoard(testBoardId);
        assertNotNull(board2);
        assertEquals(board1.getRoomId(), board2.getRoomId());
    }

    @Test
    void testCacheInvalidationOnSave() {
        // Populate cache
        RetrospectiveBoard board = retrospectiveService.getBoard(testBoardId);
        assertNotNull(board);
        
        var cache = cacheManager.getCache("retroBoards");
        var cached = cache != null ? cache.get(testBoardId) : null;
        assertNotNull(cached, "Board should be cached");
        
        // Save - should invalidate cache
        board.getUserNames().put("user1", "User1");
        retrospectiveService.save(board);
        
        // Cache should be invalidated
        cached = cache != null ? cache.get(testBoardId) : null;
        assertNull(cached, "Cache should be invalidated after save");
    }

    @Test
    void testCacheWithNonExistentBoard() {
        String nonExistentId = "nonexist";
        RetrospectiveBoard board = retrospectiveService.getBoard(nonExistentId);
        assertNull(board, "Non-existent board should return null");
        
        var cache = cacheManager.getCache("retroBoards");
        var cached = cache != null ? cache.get(nonExistentId) : null;
        assertNull(cached, "Null values should not be cached");
    }
}

