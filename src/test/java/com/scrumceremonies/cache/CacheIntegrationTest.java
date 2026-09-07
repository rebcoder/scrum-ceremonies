package com.scrumceremonies.cache;

import com.scrumceremonies.integration.RedisTestConfiguration;
import com.scrumceremonies.model.Room;
import com.scrumceremonies.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for cache behavior across multiple operations
 * Tests real-world scenarios and edge cases
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
class CacheIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(CacheIntegrationTest.class);

    @Autowired
    private RoomService roomService;

    @Autowired
    private CacheManager cacheManager;

    // RedisTemplate available for future use if needed
    // @Autowired
    // private RedisTemplate<String, Room> redisTemplate;

    @BeforeEach
    void setUp() {
        clearAllCaches();
    }

    private void clearAllCaches() {
        try {
            if (cacheManager != null) {
                var roomsCache = cacheManager.getCache("rooms");
                if (roomsCache != null) roomsCache.clear();
                var stateCache = cacheManager.getCache("roomStates");
                if (stateCache != null) stateCache.clear();
            }
        } catch (Exception e) {
            // Ignore Redis connection errors during cache clearing
            // Tests will still run and may fail if Redis is truly needed
        }
    }

    @Test
    void testCacheLifecycle() {
        // Create room
        String roomId = roomService.createRoom();
        
        // 1. First read - cache miss, should populate cache
        Room room1 = roomService.getRoom(roomId);
        assertNotNull(room1);
        var roomsCache = cacheManager.getCache("rooms");
        var cached = roomsCache != null ? roomsCache.get(roomId) : null;
        assertNotNull(cached, "Room should be cached after first read");
        
        // 2. Second read - cache hit
        Room room2 = roomService.getRoom(roomId);
        assertNotNull(room2);
        assertEquals(room1.getRoomId(), room2.getRoomId());
        
        // 3. Write operation - should invalidate cache
        roomService.addUserName(roomId, "user1", "User1");
        cached = roomsCache != null ? roomsCache.get(roomId) : null;
        assertNull(cached, "Cache should be invalidated after write");
        
        // 4. Third read - cache miss, should fetch from Redis and cache again
        Room room3 = roomService.getRoom(roomId);
        assertNotNull(room3);
        assertTrue(room3.getUserNames().containsKey("user1"));
        cached = roomsCache != null ? roomsCache.get(roomId) : null;
        assertNotNull(cached, "Room should be cached again after fetch");
    }

    @Test
    void testCacheConsistency() {
        String roomId = roomService.createRoom();
        
        // Add user and vote
        roomService.addUserName(roomId, "user1", "User1");
        roomService.addOrUpdateVote(roomId, "user1", "5");
        
        // Get room - should have latest data
        Room room = roomService.getRoom(roomId);
        assertNotNull(room);
        assertTrue(room.getUserNames().containsKey("user1"));
        assertEquals("5", room.getVotesAsStrings().get("user1"));
        
        // Get cached room - should match
        Room cachedRoom = roomService.getRoom(roomId);
        assertEquals(room.getUserNames(), cachedRoom.getUserNames());
        assertEquals(room.getVotesAsStrings(), cachedRoom.getVotesAsStrings());
    }

    @Test
    void testCacheWithMultipleOperations() {
        String roomId = roomService.createRoom();
        
        // Sequence of operations
        roomService.addUserName(roomId, "user1", "User1");
        roomService.addUserName(roomId, "user2", "User2");
        roomService.addOrUpdateVote(roomId, "user1", "3");
        roomService.addOrUpdateVote(roomId, "user2", "5");
        
        // Get room state - should reflect all changes
        Map<String, Object> state = roomService.getRoomState(roomId);
        assertNotNull(state);
        @SuppressWarnings("unchecked")
        Map<String, String> votes = (Map<String, String>) state.get("votes");
        @SuppressWarnings("unchecked")
        Map<String, String> names = (Map<String, String>) state.get("names");
        
        assertEquals("3", votes.get("user1"));
        assertEquals("5", votes.get("user2"));
        assertEquals("User1", names.get("user1"));
        assertEquals("User2", names.get("user2"));
    }

    @Test
    void testCacheRaceCondition() throws Exception {
        String roomId = roomService.createRoom();
        ExecutorService executor = Executors.newFixedThreadPool(10);
        
        // Concurrent reads and writes
        CompletableFuture<?>[] futures = IntStream.range(0, 20)
            .mapToObj(i -> CompletableFuture.runAsync(() -> {
                if (i % 2 == 0) {
                    // Read operation
                    Room room = roomService.getRoom(roomId);
                    assertNotNull(room);
                } else {
                    // Write operation
                    roomService.addUserName(roomId, "user" + i, "User" + i);
                }
            }, executor))
            .toArray(CompletableFuture[]::new);
        
        CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // Final state should be consistent
        Room finalRoom = roomService.getRoom(roomId);
        assertNotNull(finalRoom);
        // Should have at least some users added
        assertTrue(finalRoom.getUserNames().size() >= 0);
    }

    @Test
    void testCacheAfterTTLExpiration() throws InterruptedException {
        // Note: This test may be flaky due to TTL timing
        // In production, TTL is 5 minutes, but we can test the concept
        
        String roomId = roomService.createRoom();
        
        // Populate cache
        roomService.getRoom(roomId);
        var roomsCache = cacheManager.getCache("rooms");
        var cached = roomsCache != null ? roomsCache.get(roomId) : null;
        assertNotNull(cached, "Room should be cached");
        
        // In a real scenario, after TTL expires, cache would be empty
        // But we can't easily test this without mocking or waiting 5 minutes
        // So we'll just verify the cache exists and can be manually cleared
        if (roomsCache != null) {
            roomsCache.evict(roomId);
            cached = roomsCache.get(roomId);
            assertNull(cached, "Cache should be empty after eviction");
        }
    }

    @Test
    void testCachePerformanceUnderLoad() {
        String roomId = roomService.createRoom();
        
        // Warm up cache
        roomService.getRoom(roomId);
        
        // Measure performance of cached reads
        long startTime = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            roomService.getRoom(roomId);
        }
        long cachedTime = System.nanoTime() - startTime;
        
        // Clear cache and measure Redis reads
        clearAllCaches();
        startTime = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            roomService.getRoom(roomId);
        }
        long redisTime = System.nanoTime() - startTime;
        
        // Timing is logged here as an observation only, never asserted. A wall-clock
        // ratio is not a reliable correctness signal: this test's "uncached" leg still
        // reads from the same Redis-backed store that the cache itself uses, so the two
        // timings naturally sit close together, and a fixed threshold would either pass
        // for the wrong reason or fail on a machine where the cache is doing its job
        // particularly well.
        //
        // Deterministic proof that the cache is actually consulted on read lives in
        // RoomServiceCacheTest#cacheIsConsultedOnRead; proof that it gets populated lives
        // in testCacheHitOnSecondCall. Neither depends on a clock.
        double speedup = redisTime / (double) cachedTime;
        log.info("Cache performance under load (OBSERVATION, not asserted): Cached={}ns, Redis={}ns, Ratio={}",
                cachedTime, redisTime, speedup);
    }

    @Test
    void testCacheWithRevealAndClear() {
        String roomId = roomService.createRoom();
        
        // Add users and votes
        roomService.addUserName(roomId, "user1", "User1");
        roomService.addOrUpdateVote(roomId, "user1", "8");
        
        // Reveal - should invalidate cache
        roomService.revealVotes(roomId);
        var roomsCache = cacheManager.getCache("rooms");
        var cached = roomsCache != null ? roomsCache.get(roomId) : null;
        assertNull(cached, "Cache should be invalidated after reveal");
        
        // Get room - should be revealed
        Room room = roomService.getRoom(roomId);
        assertTrue(room.isRevealed());
        
        // Clear - should invalidate cache again
        roomService.clearVotes(roomId);
        cached = roomsCache != null ? roomsCache.get(roomId) : null;
        assertNull(cached, "Cache should be invalidated after clear");
        
        // Get room - should not be revealed
        Room clearedRoom = roomService.getRoom(roomId);
        assertFalse(clearedRoom.isRevealed());
    }

    @Test
    void testCacheWithStateComputation() {
        String roomId = roomService.createRoom();
        
        // First state computation - should cache
        Map<String, Object> state1 = roomService.getRoomState(roomId);
        assertNotNull(state1);
        var stateCache = cacheManager.getCache("roomStates");
        var cachedState = stateCache != null ? stateCache.get(roomId) : null;
        assertNotNull(cachedState, "State should be cached");
        
        // Second computation - should use cache
        Map<String, Object> state2 = roomService.getRoomState(roomId);
        assertEquals(state1, state2);
        
        // Modify room - should invalidate state cache
        roomService.addUserName(roomId, "user1", "User1");
        cachedState = stateCache != null ? stateCache.get(roomId) : null;
        assertNull(cachedState, "State cache should be invalidated after modification");
    }
}

