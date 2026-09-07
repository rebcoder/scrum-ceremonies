package com.scrumceremonies.integration;

import com.scrumceremonies.service.RedisRoomPresenceService;
import com.scrumceremonies.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for room join/rejoin logic.
 * Tests user presence tracking, rejoin scenarios, and race conditions.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "app.room.max-users=10",
    "app.room.user-ttl-seconds=60",
    "spring.redis.host=localhost",
    "spring.redis.port=6379"
})
class RoomJoinRejoinIntegrationTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisRoomPresenceService redisRoomPresenceService;

    private String testRoomId;

    @BeforeEach
    void setUp() {
        testRoomId = roomService.createRoom();
        assertNotNull(testRoomId, "Test room should be created");
    }

    @Test
    void testUserJoinsRoomSuccessfully() {
        String userId = "user1";
        
        RedisRoomPresenceService.JoinResult result = redisRoomPresenceService.tryJoinRoom(testRoomId, userId);
        
        assertEquals(RedisRoomPresenceService.JoinResult.SUCCESS, result);
        assertTrue(redisRoomPresenceService.isUserInRoom(testRoomId, userId));
        assertEquals(1, redisRoomPresenceService.getUserCount(testRoomId));
    }

    @Test
    void testSameUserRefreshesPage_RejoinAllowed() {
        String userId = "user1";
        
        // First join
        RedisRoomPresenceService.JoinResult result1 = redisRoomPresenceService.tryJoinRoom(testRoomId, userId);
        assertEquals(RedisRoomPresenceService.JoinResult.SUCCESS, result1);
        
        // Simulate page refresh - same user rejoins
        RedisRoomPresenceService.JoinResult result2 = redisRoomPresenceService.tryJoinRoom(testRoomId, userId);
        assertEquals(RedisRoomPresenceService.JoinResult.SUCCESS, result2);
        
        // Should still be counted as one user
        assertEquals(1, redisRoomPresenceService.getUserCount(testRoomId));
    }

    @Test
    void testSameUserTwoTabs_CountedOnce() {
        String userId = "user1";
        
        // Simulate two tabs - same userId
        RedisRoomPresenceService.JoinResult result1 = redisRoomPresenceService.tryJoinRoom(testRoomId, userId);
        RedisRoomPresenceService.JoinResult result2 = redisRoomPresenceService.tryJoinRoom(testRoomId, userId);
        
        assertEquals(RedisRoomPresenceService.JoinResult.SUCCESS, result1);
        assertEquals(RedisRoomPresenceService.JoinResult.SUCCESS, result2);
        
        // Should be counted as one user
        assertEquals(1, redisRoomPresenceService.getUserCount(testRoomId));
    }

    @Test
    void testUserRejoinsWithinTTL_Allowed() throws InterruptedException {
        String userId = "user1";
        
        // First join
        redisRoomPresenceService.tryJoinRoom(testRoomId, userId);
        redisRoomPresenceService.updateUserActivity(testRoomId, userId);
        
        // Wait a bit (but less than TTL)
        Thread.sleep(1000);
        
        // Rejoin within TTL
        RedisRoomPresenceService.JoinResult result = redisRoomPresenceService.tryJoinRoom(testRoomId, userId);
        assertEquals(RedisRoomPresenceService.JoinResult.SUCCESS, result);
        assertEquals(1, redisRoomPresenceService.getUserCount(testRoomId));
    }

    @Test
    void testRoomReachesMaxUsers_EleventhUserRejected() {
        // Fill room with 10 users
        for (int i = 1; i <= 10; i++) {
            String userId = "user" + i;
            RedisRoomPresenceService.JoinResult result = redisRoomPresenceService.tryJoinRoom(testRoomId, userId);
            assertEquals(RedisRoomPresenceService.JoinResult.SUCCESS, result);
        }
        
        assertEquals(10, redisRoomPresenceService.getUserCount(testRoomId));
        
        // 11th user should be rejected
        String user11 = "user11";
        RedisRoomPresenceService.JoinResult result = redisRoomPresenceService.tryJoinRoom(testRoomId, user11);
        assertEquals(RedisRoomPresenceService.JoinResult.ROOM_FULL, result);
        assertEquals(10, redisRoomPresenceService.getUserCount(testRoomId));
    }

    @Test
    void testUserLeaves_SlotFreed() {
        // Add 10 users
        for (int i = 1; i <= 10; i++) {
            redisRoomPresenceService.tryJoinRoom(testRoomId, "user" + i);
        }
        
        assertEquals(10, redisRoomPresenceService.getUserCount(testRoomId));
        
        // Remove one user
        redisRoomPresenceService.removeUser(testRoomId, "user1");
        assertEquals(9, redisRoomPresenceService.getUserCount(testRoomId));
        
        // Now 11th user should be able to join
        RedisRoomPresenceService.JoinResult result = redisRoomPresenceService.tryJoinRoom(testRoomId, "user11");
        assertEquals(RedisRoomPresenceService.JoinResult.SUCCESS, result);
        assertEquals(10, redisRoomPresenceService.getUserCount(testRoomId));
    }

    @Test
    void testConcurrentJoinsAtCapacity_RaceConditionSafe() throws InterruptedException {
        // Fill room with 9 users
        for (int i = 1; i <= 9; i++) {
            redisRoomPresenceService.tryJoinRoom(testRoomId, "user" + i);
        }
        
        assertEquals(9, redisRoomPresenceService.getUserCount(testRoomId));
        
        // Try to add 5 users concurrently (should allow exactly 1)
        int numThreads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger fullCount = new AtomicInteger(0);
        
        for (int i = 10; i <= 14; i++) {
            final int userIdNum = i;
            executor.submit(() -> {
                try {
                    RedisRoomPresenceService.JoinResult result = 
                        redisRoomPresenceService.tryJoinRoom(testRoomId, "user" + userIdNum);
                    if (result == RedisRoomPresenceService.JoinResult.SUCCESS) {
                        successCount.incrementAndGet();
                    } else if (result == RedisRoomPresenceService.JoinResult.ROOM_FULL) {
                        fullCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        // Should have exactly 1 success and 4 full
        assertEquals(1, successCount.get(), "Exactly one user should join");
        assertEquals(4, fullCount.get(), "Four users should be rejected");
        assertEquals(10, redisRoomPresenceService.getUserCount(testRoomId), "Room should have exactly 10 users");
    }

    @Test
    void testActivityUpdates_LastSeenUpdated() throws InterruptedException {
        String userId = "user1";
        
        // Join
        redisRoomPresenceService.tryJoinRoom(testRoomId, userId);
        
        // Get initial lastSeen
        var users1 = redisRoomPresenceService.getUsersWithPresence(testRoomId);
        assertTrue(users1.containsKey(userId));
        long initialLastSeen = Long.parseLong((String) users1.get(userId));
        
        // Wait a bit
        Thread.sleep(100);
        
        // Update activity
        redisRoomPresenceService.updateUserActivity(testRoomId, userId);
        
        // Get updated lastSeen
        var users2 = redisRoomPresenceService.getUsersWithPresence(testRoomId);
        long updatedLastSeen = Long.parseLong((String) users2.get(userId));
        
        assertTrue(updatedLastSeen > initialLastSeen, "lastSeen should be updated");
    }

    @Test
    void testMultipleRooms_IndependentLimits() {
        String room1 = roomService.createRoom();
        String room2 = roomService.createRoom();
        
        // Fill both rooms with 10 users each
        for (int i = 1; i <= 10; i++) {
            redisRoomPresenceService.tryJoinRoom(room1, "user" + i);
            redisRoomPresenceService.tryJoinRoom(room2, "user" + i);
        }
        
        assertEquals(10, redisRoomPresenceService.getUserCount(room1));
        assertEquals(10, redisRoomPresenceService.getUserCount(room2));
        
        // Both should reject 11th user
        assertEquals(RedisRoomPresenceService.JoinResult.ROOM_FULL, 
                     redisRoomPresenceService.tryJoinRoom(room1, "user11"));
        assertEquals(RedisRoomPresenceService.JoinResult.ROOM_FULL, 
                     redisRoomPresenceService.tryJoinRoom(room2, "user11"));
    }

    /**
     * The cleanup pass scans keys matching {@code room:*}, but presence sub-keys such as
     * {@code room:<id>:users} are Redis hashes, not the JSON-serialized room value.
     * If the scan included them, reading one as a room would raise WRONGTYPE and abort
     * the whole cleanup pass. {@code getAllRoomKeys} must return only genuine 8-character
     * room keys, excluding any sub-key suffixes.
     */
    @org.junit.jupiter.api.Test
    void getAllRoomKeys_excludesPresenceSubKeys() {
        String roomId = roomService.createRoom();
        String presenceKey = "room:" + roomId + ":users";
        stringRedisTemplate.opsForHash().put(presenceKey, "user1", "Tester");
        try {
            java.util.Set<String> keys = roomService.getAllRoomKeys();
            org.assertj.core.api.Assertions.assertThat(keys).contains("room:" + roomId);
            org.assertj.core.api.Assertions.assertThat(keys).doesNotContain(presenceKey);
        } finally {
            stringRedisTemplate.delete(presenceKey);
            roomService.deleteRoom(roomId);
        }
    }
}

