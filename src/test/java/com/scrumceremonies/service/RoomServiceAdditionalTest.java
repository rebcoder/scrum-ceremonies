package com.scrumceremonies.service;

import com.scrumceremonies.model.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

/**
 * Additional unit tests for RoomService.
 * Tests cleanup logic, deletion, and edge cases.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoomServiceAdditionalTest {

    @Mock
    private RedisTemplate<String, Room> redisTemplate;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, Room> valueOperations;

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        roomService = new RoomService(redisTemplate);
        
        // Inject stringRedisTemplate using reflection
        ReflectionTestUtils.setField(roomService, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    void testDeleteRoom() {
        // Given
        String roomId = "test1234";
        String roomKey = "room:" + roomId;
        String presenceKey = "room:" + roomId + ":presence";

        // When
        roomService.deleteRoom(roomId);

        // Then
        // Verify both room key and presence key are deleted
        verify(redisTemplate).delete(roomKey);
        verify(stringRedisTemplate).delete(presenceKey);
    }

    @Test
    void testDeleteRoomWithNullId() {
        // Given
        String roomId = null;

        // When
        roomService.deleteRoom(roomId);

        // Then
        // Should not throw exception, but may delete null keys (Redis handles this)
        verify(redisTemplate).delete("room:null");
        verify(stringRedisTemplate).delete("room:null:presence");
    }
}

