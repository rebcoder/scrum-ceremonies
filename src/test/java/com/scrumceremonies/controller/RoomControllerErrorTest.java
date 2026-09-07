package com.scrumceremonies.controller;

import com.scrumceremonies.model.Room;
import com.scrumceremonies.util.ClientIpResolver;
import com.scrumceremonies.service.RateLimitService;
import com.scrumceremonies.service.RedisRoomPresenceService;
import com.scrumceremonies.service.RedisRoomUserService;
import com.scrumceremonies.service.RoomLimitService;
import com.scrumceremonies.service.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import com.scrumceremonies.service.VisitorAnalyticsService;
import com.scrumceremonies.config.TestSecurityConfig;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Error-path tests for RoomController.
 * Tests invalid inputs, error responses, and exception handling.
 */
@WebMvcTest(
    controllers = RoomController.class,
    excludeAutoConfiguration = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
    }
)
@Import(TestSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class RoomControllerErrorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoomService roomService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private RoomLimitService roomLimitService;

    @MockitoBean
    private RedisRoomUserService redisRoomUserService;

    @MockitoBean
    private RedisRoomPresenceService redisRoomPresenceService;

    @MockitoBean
    private SimpMessagingTemplate simpMessagingTemplate;

    @MockitoBean
    private VisitorAnalyticsService visitorAnalyticsService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @org.junit.jupiter.api.BeforeEach
    void setUpClientIpResolver() {
        when(clientIpResolver.resolve(any())).thenReturn("127.0.0.1");
    }

    @Test
    void testCreateRoomRateLimitExceeded() throws Exception {
        // Given
        when(rateLimitService.canCreateRoom(anyString())).thenReturn(false);

        // When & Then
        mockMvc.perform(post("/api/create-room"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string(containsString("Too many rooms created")));

        verify(rateLimitService).canCreateRoom(anyString());
        verify(roomService, never()).createRoom();
    }

    @Test
    void testCreateRoomLimitExceeded() throws Exception {
        // Given
        when(rateLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(roomLimitService.canCreateRoom(anyString())).thenReturn(false);

        // When & Then
        mockMvc.perform(post("/api/create-room"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("maximum number of active rooms")));

        verify(rateLimitService).canCreateRoom(anyString());
        verify(roomLimitService).canCreateRoom(anyString());
        verify(roomService, never()).createRoom();
    }

    @Test
    void testCreateRoomRedisUnavailable() throws Exception {
        // Given
        when(rateLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(roomLimitService.canCreateRoom(anyString())).thenReturn(true);
        when(roomService.createRoom()).thenThrow(new RedisConnectionFailureException("Redis unavailable"));

        // When & Then
        mockMvc.perform(post("/api/create-room"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("temporarily unavailable")));
    }

    @Test
    void testJoinRoomInvalidRoomId() throws Exception {
        // Given
        String invalidRoomId = "invalid";

        // When & Then
        mockMvc.perform(post("/api/join-room")
                .param("roomId", invalidRoomId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Invalid room code format")));

        verify(roomService, never()).getRoom(anyString());
    }

    @Test
    void testJoinRoomRoomIdTooLong() throws Exception {
        // Given
        String longRoomId = "a".repeat(101);

        // When & Then
        mockMvc.perform(post("/api/join-room")
                .param("roomId", longRoomId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Invalid room code format")));
    }

    @Test
    void testJoinRoomUserIdTooLong() throws Exception {
        // Given
        String roomId = "test1234";
        String longUserId = "a".repeat(1001);

        // When & Then
        mockMvc.perform(post("/api/join-room")
                .param("roomId", roomId)
                .param("userId", longUserId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("User ID is too long")));
    }

    @Test
    void testJoinRoomNotFound() throws Exception {
        // Given
        String roomId = "test1234";
        when(roomService.getRoom(roomId)).thenReturn(null);

        // When & Then
        mockMvc.perform(post("/api/join-room")
                .param("roomId", roomId))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Room not found"));

        verify(roomService).getRoom(roomId);
    }

    @Test
    void testJoinRoomFull() throws Exception {
        // Given
        String roomId = "test1234";
        String userId = "user1";
        Room room = new Room();
        room.setRoomId(roomId);
        ConcurrentHashMap<String, String> userNames = new ConcurrentHashMap<>();
        // Add 10 users (max capacity)
        for (int i = 0; i < 10; i++) {
            userNames.put("user" + i, "User" + i);
        }
        room.setUserNames(userNames);

        when(roomService.getRoom(roomId)).thenReturn(room);
        when(redisRoomPresenceService.tryJoinRoom(roomId, userId))
                .thenReturn(RedisRoomPresenceService.JoinResult.ROOM_FULL);

        // When & Then
        mockMvc.perform(post("/api/join-room")
                .param("roomId", roomId)
                .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("room is full")));

        verify(redisRoomPresenceService).tryJoinRoom(roomId, userId);
    }

    @Test
    void testJoinRoomUnableToJoin() throws Exception {
        // Given
        String roomId = "test1234";
        String userId = "user1";
        Room room = new Room();
        room.setRoomId(roomId);
        room.setUserNames(new ConcurrentHashMap<>());

        when(roomService.getRoom(roomId)).thenReturn(room);
        // Use ERROR to simulate failure
        when(redisRoomPresenceService.tryJoinRoom(roomId, userId))
                .thenReturn(RedisRoomPresenceService.JoinResult.ERROR);

        // When & Then
        mockMvc.perform(post("/api/join-room")
                .param("roomId", roomId)
                .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Unable to join room")));
    }

    @Test
    void testGetVotesInvalidRoomId() throws Exception {
        // Given
        String invalidRoomId = "invalid";

        // When & Then
        mockMvc.perform(get("/api/votes")
                .param("roomId", invalidRoomId))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));

        verify(roomService, never()).getVotes(anyString());
    }

    @Test
    void testGetRoomStateInvalidRoomId() throws Exception {
        // Given
        String invalidRoomId = "invalid";

        // When & Then
        mockMvc.perform(get("/api/room-state")
                .param("roomId", invalidRoomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isEmpty())
                .andExpect(jsonPath("$.revealed").value(false))
                .andExpect(jsonPath("$.votes").isEmpty());

        verify(roomService, never()).getRoomState(anyString());
    }

    @Test
    void testLeaveRoomInvalidRoomId() throws Exception {
        // Given
        String invalidRoomId = "invalid";
        String userId = "user1";

        // When & Then
        mockMvc.perform(post("/api/leave-room")
                .param("roomId", invalidRoomId)
                .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Invalid room code format")));
    }

    @Test
    void testLeaveRoomRoomIdTooLong() throws Exception {
        // Given
        String longRoomId = "a".repeat(101);
        String userId = "user1";

        // When & Then
        mockMvc.perform(post("/api/leave-room")
                .param("roomId", longRoomId)
                .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Invalid room code format")));
    }

    @Test
    void testLeaveRoomUserIdTooLong() throws Exception {
        // Given
        String roomId = "test1234";
        String longUserId = "a".repeat(1001);

        // When & Then
        mockMvc.perform(post("/api/leave-room")
                .param("roomId", roomId)
                .param("userId", longUserId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("User ID is too long")));
    }
}

