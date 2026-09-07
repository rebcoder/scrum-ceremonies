package com.scrumceremonies.service;

import com.scrumceremonies.model.Room;
import com.scrumceremonies.model.RetrospectiveBoard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RoomLimitService.
 * Tests room limit enforcement, session tracking, and edge cases.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoomLimitServiceTest {

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Mock
    private RedisTemplate<String, Room> roomRedisTemplate;

    @Mock
    private RedisTemplate<String, RetrospectiveBoard> retroRedisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private ValueOperations<String, Room> roomValueOperations;

    @Mock
    private ValueOperations<String, RetrospectiveBoard> retroValueOperations;

    private RoomLimitService roomLimitService;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(roomRedisTemplate.opsForValue()).thenReturn(roomValueOperations);
        when(retroRedisTemplate.opsForValue()).thenReturn(retroValueOperations);
        
        roomLimitService = new RoomLimitService(
            stringRedisTemplate,
            roomRedisTemplate,
            retroRedisTemplate
        );
        ReflectionTestUtils.setField(roomLimitService, "maxActiveRoomsPerSession", 3);
    }

    @Test
    void testCanCreateRoomNoActiveRooms() {
        // Given
        String sessionId = "session1";
        when(setOperations.members("rooms:session:session1")).thenReturn(new HashSet<>());

        // When
        boolean result = roomLimitService.canCreateRoom(sessionId);

        // Then
        assertTrue(result);
    }

    @Test
    void testCanCreateRoomNullSession() {
        // When
        boolean result = roomLimitService.canCreateRoom(null);

        // Then
        assertTrue(result);
        verify(setOperations, never()).members(anyString());
    }

    @Test
    void testCanCreateRoomUnknownSession() {
        // When
        boolean result = roomLimitService.canCreateRoom("unknown");

        // Then
        assertTrue(result);
        verify(setOperations, never()).members(anyString());
    }

    @Test
    void testCanCreateRoomAtLimit() {
        // Given
        String sessionId = "session1";
        Set<String> roomCodes = new HashSet<>();
        roomCodes.add("room1");
        roomCodes.add("room2");
        roomCodes.add("room3");
        
        when(setOperations.members("rooms:session:session1")).thenReturn(roomCodes);
        
        // All rooms are active (within 30 minutes)
        Room room1 = new Room();
        room1.setLastActivityTime(System.currentTimeMillis() - 10 * 60 * 1000); // 10 minutes ago
        Room room2 = new Room();
        room2.setLastActivityTime(System.currentTimeMillis() - 5 * 60 * 1000); // 5 minutes ago
        Room room3 = new Room();
        room3.setLastActivityTime(System.currentTimeMillis() - 1 * 60 * 1000); // 1 minute ago
        
        when(roomValueOperations.get("room:room1")).thenReturn(room1);
        when(roomValueOperations.get("room:room2")).thenReturn(room2);
        when(roomValueOperations.get("room:room3")).thenReturn(room3);

        // When
        boolean result = roomLimitService.canCreateRoom(sessionId);

        // Then
        assertFalse(result);
    }

    @Test
    void testCanCreateRoomWithInactiveRooms() {
        // Given
        String sessionId = "session1";
        Set<String> roomCodes = new HashSet<>();
        roomCodes.add("room1"); // Active
        roomCodes.add("room2"); // Inactive (> 30 minutes)
        
        when(setOperations.members("rooms:session:session1")).thenReturn(roomCodes);
        
        Room room1 = new Room();
        room1.setLastActivityTime(System.currentTimeMillis() - 10 * 60 * 1000); // 10 minutes ago (active)
        Room room2 = new Room();
        room2.setLastActivityTime(System.currentTimeMillis() - 40 * 60 * 1000); // 40 minutes ago (inactive)
        
        when(roomValueOperations.get("room:room1")).thenReturn(room1);
        when(roomValueOperations.get("room:room2")).thenReturn(room2);

        // When
        boolean result = roomLimitService.canCreateRoom(sessionId);

        // Then
        assertTrue(result);
        verify(setOperations).remove("rooms:session:session1", "room2");
    }

    @Test
    void testCanCreateRoomWithRetroBoards() {
        // Given
        String sessionId = "session1";
        Set<String> roomCodes = new HashSet<>();
        roomCodes.add("retro1");
        
        when(setOperations.members("rooms:session:session1")).thenReturn(roomCodes);
        when(roomValueOperations.get("room:retro1")).thenReturn(null);
        
        RetrospectiveBoard board = new RetrospectiveBoard();
        board.setLastActivityTime(System.currentTimeMillis() - 10 * 60 * 1000);
        when(retroValueOperations.get("retro:retro1")).thenReturn(board);

        // When
        boolean result = roomLimitService.canCreateRoom(sessionId);

        // Then
        assertTrue(result);
    }

    @Test
    void testCanCreateRoomRedisFailure() {
        // Given
        String sessionId = "session1";
        when(setOperations.members(anyString())).thenThrow(new RuntimeException("Redis error"));

        // When
        boolean result = roomLimitService.canCreateRoom(sessionId);

        // Then
        assertTrue(result); // Fail open
    }

    @Test
    void testRegisterRoomCreation() {
        // Given
        String sessionId = "session1";
        String roomCode = "room1234";
        when(stringRedisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        // When
        roomLimitService.registerRoomCreation(sessionId, roomCode);

        // Then
        verify(setOperations).add("rooms:session:session1", roomCode);
        verify(stringRedisTemplate).expire("rooms:session:session1", 24, TimeUnit.HOURS);
    }

    @Test
    void testRegisterRoomCreationNullSession() {
        // When
        roomLimitService.registerRoomCreation(null, "room1234");

        // Then
        verify(setOperations, never()).add(anyString(), anyString());
    }

    @Test
    void testRegisterRoomCreationEmptyRoomCode() {
        // When
        roomLimitService.registerRoomCreation("session1", "");

        // Then
        verify(setOperations, never()).add(anyString(), anyString());
    }

    @Test
    void testRegisterRoomCreationRedisFailure() {
        // Given
        String sessionId = "session1";
        String roomCode = "room1234";
        when(setOperations.add(anyString(), anyString())).thenThrow(new RuntimeException("Redis error"));

        // When
        roomLimitService.registerRoomCreation(sessionId, roomCode);

        // Then
        // Should not throw - registration failure shouldn't block
    }

    @Test
    void testGetActiveRoomCount() {
        // Given
        String sessionId = "session1";
        Set<String> roomCodes = new HashSet<>();
        roomCodes.add("room1");
        roomCodes.add("room2");
        
        when(setOperations.members("rooms:session:session1")).thenReturn(roomCodes);
        
        Room room1 = new Room();
        room1.setLastActivityTime(System.currentTimeMillis() - 10 * 60 * 1000);
        Room room2 = new Room();
        room2.setLastActivityTime(System.currentTimeMillis() - 40 * 60 * 1000); // Inactive
        
        when(roomValueOperations.get("room:room1")).thenReturn(room1);
        when(roomValueOperations.get("room:room2")).thenReturn(room2);

        // When
        int count = roomLimitService.getActiveRoomCount(sessionId);

        // Then
        assertEquals(1, count);
    }

    @Test
    void testGetActiveRoomCountNullSession() {
        // When
        int count = roomLimitService.getActiveRoomCount(null);

        // Then
        assertEquals(0, count);
    }

    @Test
    void testGetActiveRoomCountRedisFailure() {
        // Given
        String sessionId = "session1";
        when(setOperations.members(anyString())).thenThrow(new RuntimeException("Redis error"));

        // When
        int count = roomLimitService.getActiveRoomCount(sessionId);

        // Then
        assertEquals(0, count);
    }
}

