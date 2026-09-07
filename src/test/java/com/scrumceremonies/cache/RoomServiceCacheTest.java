package com.scrumceremonies.cache;

import com.scrumceremonies.model.Room;
import com.scrumceremonies.service.RoomService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for RoomService caching
 * Tests cache hits, misses, invalidation, and concurrent access
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
class RoomServiceCacheTest {
    private static final Logger log = LoggerFactory.getLogger(RoomServiceCacheTest.class);

    @Autowired
    private RoomService roomService;

    @Autowired
    private CacheManager cacheManager;

    // RedisTemplate available for future use if needed
    // @Autowired
    // private RedisTemplate<String, Room> redisTemplate;

    private String testRoomId;

    @BeforeEach
    void setUp() {
        // Clear caches before each test
        clearAllCaches();
        
        // Create a test room
        testRoomId = roomService.createRoom();
        assertNotNull(testRoomId, "Room should be created");
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
    void testCacheHitOnSecondCall() {
        // First call - should hit Redis (cache miss)
        Room room1 = roomService.getRoom(testRoomId);
        assertNotNull(room1, "Room should be retrieved");
        
        // Verify room is in cache
        var roomsCache = cacheManager.getCache("rooms");
        var cachedRoom = roomsCache != null ? roomsCache.get(testRoomId) : null;
        assertNotNull(cachedRoom, "Room should be cached after first call");
        
        // Second call - should hit cache (cache hit)
        Room room2 = roomService.getRoom(testRoomId);
        assertNotNull(room2, "Room should be retrieved from cache");
        assertEquals(room1.getRoomId(), room2.getRoomId(), "Cached room should match");
    }

    @Test
    void testCacheInvalidationOnVote() {
        // Get room to populate cache
        Room room1 = roomService.getRoom(testRoomId);
        assertNotNull(room1);
        
        // Verify cache is populated
        var roomsCache = cacheManager.getCache("rooms");
        assertNotNull(roomsCache, "Rooms cache should exist");
        assertNotNull(roomsCache.get(testRoomId), "Room should be in cache");
        
        // Add a vote - should invalidate cache
        roomService.addUserName(testRoomId, "user1", "TestUser");
        roomService.addOrUpdateVote(testRoomId, "user1", "5");
        
        // Cache should be invalidated
        var roomsCache2 = cacheManager.getCache("rooms");
        assertNotNull(roomsCache2, "Rooms cache should exist");
        assertNull(roomsCache2.get(testRoomId), "Room cache should be invalidated after vote");
        
        // Next call should fetch from Redis and cache again
        Room room2 = roomService.getRoom(testRoomId);
        assertNotNull(room2);
        assertNotNull(roomsCache.get(testRoomId), "Room should be cached again after fetch");
    }

    @Test
    void testCacheInvalidationOnReveal() {
        // Populate cache
        roomService.getRoom(testRoomId);
        var roomsCache = cacheManager.getCache("rooms");
        assertNotNull(roomsCache, "Rooms cache should exist");
        assertNotNull(roomsCache.get(testRoomId));
        
        // Reveal votes - should invalidate cache
        roomService.revealVotes(testRoomId);
        
        // Cache should be invalidated
        var roomsCache2 = cacheManager.getCache("rooms");
        assertNotNull(roomsCache2, "Rooms cache should exist");
        assertNull(roomsCache2.get(testRoomId), "Room cache should be invalidated after reveal");
    }

    @Test
    void testCacheInvalidationOnClear() {
        // Populate cache
        roomService.getRoom(testRoomId);
        var roomsCache = cacheManager.getCache("rooms");
        assertNotNull(roomsCache, "Rooms cache should exist");
        assertNotNull(roomsCache.get(testRoomId));
        
        // Clear votes - should invalidate cache
        roomService.clearVotes(testRoomId);
        
        // Cache should be invalidated
        var roomsCache2 = cacheManager.getCache("rooms");
        assertNotNull(roomsCache2, "Rooms cache should exist");
        assertNull(roomsCache2.get(testRoomId), "Room cache should be invalidated after clear");
    }

    @Test
    void testRoomStateCaching() {
        // First call - cache miss
        Map<String, Object> state1 = roomService.getRoomState(testRoomId);
        assertNotNull(state1);
        
        // Verify state is cached
        var stateCache = cacheManager.getCache("roomStates");
        assertNotNull(stateCache, "Room states cache should exist");
        assertNotNull(stateCache.get(testRoomId), "Room state should be cached");
        
        // Second call - cache hit
        Map<String, Object> state2 = roomService.getRoomState(testRoomId);
        assertNotNull(state2);
        assertEquals(state1.get("votes"), state2.get("votes"), "Cached state votes should match");
        assertEquals(state1.get("names"), state2.get("names"), "Cached state names should match");
    }

    @Test
    void testRoomStateInvalidationOnVote() {
        // Populate state cache
        roomService.getRoomState(testRoomId);
        var stateCache = cacheManager.getCache("roomStates");
        assertNotNull(stateCache, "Room states cache should exist");
        assertNotNull(stateCache.get(testRoomId));
        
        // Add vote - should invalidate state cache
        roomService.addUserName(testRoomId, "user1", "TestUser");
        roomService.addOrUpdateVote(testRoomId, "user1", "8");
        
        // State cache should be invalidated
        var stateCache2 = cacheManager.getCache("roomStates");
        assertNotNull(stateCache2, "Room states cache should exist");
        assertNull(stateCache2.get(testRoomId), "Room state cache should be invalidated after vote");
    }

    @Test
    void testConcurrentCacheAccess() throws InterruptedException {
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // Concurrent reads should all succeed and use cache
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    Room room = roomService.getRoom(testRoomId);
                    if (room != null) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        assertEquals(numThreads, successCount.get(), "All concurrent reads should succeed");
        assertEquals(0, errorCount.get(), "No errors should occur during concurrent access");
    }

    @Test
    void testCacheWithNonExistentRoom() {
        String nonExistentRoomId = "nonexist";
        
        // Should return null and not cache null values
        Room room = roomService.getRoom(nonExistentRoomId);
        assertNull(room, "Non-existent room should return null");
        
        // Null should not be cached (based on unless = "#result == null")
        var roomsCache = cacheManager.getCache("rooms");
        assertNotNull(roomsCache, "Rooms cache should exist");
        assertNull(roomsCache.get(nonExistentRoomId), "Null values should not be cached");
    }

    @Test
    void testCacheAfterRoomUpdate() {
        // Get room
        Room room1 = roomService.getRoom(testRoomId);
        assertNotNull(room1);
        
        // Add user
        roomService.addUserName(testRoomId, "user1", "User1");
        
        // Get room again - should fetch fresh from Redis
        Room room2 = roomService.getRoom(testRoomId);
        assertNotNull(room2);
        assertTrue(room2.getUserNames().containsKey("user1"), "Room should have updated user");
    }

    @Test
    void testMultipleCacheInvalidations() {
        // Populate both caches
        roomService.getRoom(testRoomId);
        roomService.getRoomState(testRoomId);
        
        var roomsCache = cacheManager.getCache("rooms");
        var stateCache = cacheManager.getCache("roomStates");
        
        assertNotNull(roomsCache, "Rooms cache should exist");
        assertNotNull(stateCache, "Room states cache should exist");
        assertNotNull(roomsCache.get(testRoomId));
        assertNotNull(stateCache.get(testRoomId));
        
        // Single write operation should invalidate both
        roomService.addUserName(testRoomId, "user1", "User1");
        roomService.addOrUpdateVote(testRoomId, "user1", "13");
        
        assertNull(roomsCache.get(testRoomId), "Rooms cache should be invalidated");
        assertNull(stateCache.get(testRoomId), "Room states cache should be invalidated");
    }

    /**
     * Proves that reads actually go through the cache, without relying on timing.
     *
     * <p>A wall-clock comparison is not a meaningful way to test this: the {@code rooms}
     * cache <b>is</b> backed by Redis (the {@code @Primary RedisCacheManager}), so a
     * "cached" read and an "uncached" read would hit the very same store, and any timing
     * threshold between them would be measuring the machine's variance, not the presence
     * of a cache. Worse, it would be liable to fail on a slower machine even though
     * nothing is actually wrong.
     *
     * <p>Instead, this plants a sentinel value directly in the cache, bypassing the
     * service, and confirms that a subsequent read returns the sentinel rather than the
     * real data from the backing store. That deterministically proves the cache is
     * consulted on read. {@code testCacheHitOnSecondCall} already proves the cache gets
     * <i>populated</i>, which is a different claim — a read path that ignored the cache
     * entirely would still satisfy that one.
     */
    @Test
    void cacheIsConsultedOnRead() {
        final String SENTINEL = "SENTINEL-not-a-real-host";

        // Positive control, first: without confirming the room is readable and the cache
        // is populated by a normal read, every assertion below would pass vacuously on a
        // system where the room can't be read or no cache is configured at all.
        Room first = roomService.getRoom(testRoomId);
        assertNotNull(first, "CONTROL FAILED: the room must be readable before cache behaviour "
                + "can be asserted about it");
        var roomsCache = cacheManager.getCache("rooms");
        assertNotNull(roomsCache, "CONTROL FAILED: no 'rooms' cache is configured");
        assertNotNull(roomsCache.get(testRoomId),
                "CONTROL FAILED: a normal read did not populate the cache, so the read below "
                        + "cannot demonstrate anything about cache consultation");

        // Plant a sentinel directly in the cache, bypassing the service. This must not be
        // rewritten to go through roomService.saveRoom(...) instead — that path carries
        // @CacheEvict, so the read below would then legitimately return fresh data and
        // invert the assertion below.
        Room planted = roomService.getRoom(testRoomId);
        planted.setHostName(SENTINEL);
        roomsCache.put(testRoomId, planted);

        // A read served from the cache returns the sentinel; a read that went to the
        // backing store would return the room's real host name instead. No timing involved.
        Room second = roomService.getRoom(testRoomId);
        assertNotNull(second, "room disappeared between reads");
        assertEquals(SENTINEL, second.getHostName(),
                "getRoom() did not consult the 'rooms' cache — it returned the backing store's "
                        + "value while a different value sat in the cache. The @Cacheable read "
                        + "path is not being taken.");
    }
}

