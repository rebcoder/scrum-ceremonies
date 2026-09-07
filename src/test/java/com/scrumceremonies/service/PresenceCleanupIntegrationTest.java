package com.scrumceremonies.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify that room/board deletion also deletes presence hash.
 * This test verifies the fix for orphaned presence data that could cause:
 * - Ghost users
 * - False "room full" detection
 * - Incorrect concurrent join behavior
 */
@SpringBootTest
@TestPropertySource(properties = {
    "app.room.max-users=10",
    "app.room.user-ttl-seconds=60"
})
class PresenceCleanupIntegrationTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private RetrospectiveService retrospectiveService;

    @Autowired
    private RedisRoomPresenceService redisRoomPresenceService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testDeleteRoomAlsoDeletesPresence() {
        // Given: Create a room and add a user to presence
        String roomId = roomService.createRoom();
        String userId = "test-user-1";
        
        // Add user to presence
        RedisRoomPresenceService.JoinResult result = redisRoomPresenceService.tryJoinRoom(roomId, userId);
        assertEquals(RedisRoomPresenceService.JoinResult.SUCCESS, result);
        
        // Verify presence exists
        assertTrue(redisRoomPresenceService.isUserInRoom(roomId, userId));
        String presenceKey = "room:" + roomId + ":presence";
        assertTrue(stringRedisTemplate.hasKey(presenceKey), "Presence key should exist");
        
        // When: Delete the room
        roomService.deleteRoom(roomId);
        
        // Then: Both room key and presence key should be deleted
        assertFalse(stringRedisTemplate.hasKey("room:" + roomId), "Room key should be deleted");
        assertFalse(stringRedisTemplate.hasKey(presenceKey), "Presence key should be deleted");
        assertFalse(redisRoomPresenceService.isUserInRoom(roomId, userId), "User should not be in room after deletion");
    }

    @Test
    void testDeleteBoardAlsoDeletesPresence() {
        // Given: Create a retro board and add a user to presence
        String boardId = retrospectiveService.createBoard();
        String userId = "test-user-2";
        
        // Add user to presence
        RedisRoomPresenceService.JoinResult result = redisRoomPresenceService.tryJoinRoom(boardId, userId);
        assertEquals(RedisRoomPresenceService.JoinResult.SUCCESS, result);
        
        // Verify presence exists
        assertTrue(redisRoomPresenceService.isUserInRoom(boardId, userId));
        String presenceKey = "room:" + boardId + ":presence";
        assertTrue(stringRedisTemplate.hasKey(presenceKey), "Presence key should exist");
        
        // When: Delete the board
        retrospectiveService.deleteBoard(boardId);
        
        // Then: Both board key and presence key should be deleted
        assertFalse(stringRedisTemplate.hasKey("retro:" + boardId), "Board key should be deleted");
        assertFalse(stringRedisTemplate.hasKey(presenceKey), "Presence key should be deleted");
        assertFalse(redisRoomPresenceService.isUserInRoom(boardId, userId), "User should not be in room after deletion");
    }

    @Test
    void testDeleteRoomWithMultipleUsersInPresence() {
        // Given: Create a room and add multiple users to presence
        String roomId = roomService.createRoom();
        
        // Add multiple users
        for (int i = 1; i <= 5; i++) {
            RedisRoomPresenceService.JoinResult result = redisRoomPresenceService.tryJoinRoom(roomId, "user" + i);
            assertEquals(RedisRoomPresenceService.JoinResult.SUCCESS, result);
        }
        
        // Verify all users are in presence
        assertEquals(5, redisRoomPresenceService.getUserCount(roomId));
        String presenceKey = "room:" + roomId + ":presence";
        assertTrue(stringRedisTemplate.hasKey(presenceKey), "Presence key should exist");
        
        // When: Delete the room
        roomService.deleteRoom(roomId);
        
        // Then: Presence key should be deleted (all users removed)
        assertFalse(stringRedisTemplate.hasKey(presenceKey), "Presence key should be deleted");
        assertEquals(0, redisRoomPresenceService.getUserCount(roomId), "User count should be 0 after deletion");
    }
}

